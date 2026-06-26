package tw.zyx.serverscope.core;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.exporter.otlp.http.metrics.OtlpHttpMetricExporter;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.time.Duration;
import java.util.Map;

/**
 * Platform-agnostic telemetry core. Hand-built OpenTelemetry SDK (no autoconfigure
 * -> no ServiceLoader surprises), OTLP/HTTP over the JDK sender (no okhttp/kotlin).
 *
 * Three signal types:
 *   - event(name)              : discrete, no detail   -> span + counter(event=name)
 *   - event(name, type, kv...) : discrete with detail  -> span carries every kv pair
 *                                (full detail, queryable in traces); counter carries
 *                                only the name + optional low-cardinality type label
 *   - count(name, type)        : high-freq             -> counter only (no span flood)
 *   - sampleTick/Counts        : gauges                -> async, read latest sampled values
 *
 * Cardinality rule: high-cardinality values (player names, advancement ids, coords,
 * free text such as commands/death messages) go ONLY onto spans. Metric labels stay
 * low-cardinality (event name + a bounded `type`) so the metrics backend never blows up.
 */
public final class Telemetry {
    private static final AttributeKey<String> EVENT = AttributeKey.stringKey("event");
    private static final AttributeKey<String> TYPE = AttributeKey.stringKey("type");

    private static volatile Tracer tracer;
    private static volatile LongCounter eventCounter;

    // latest sampled values — written from ServerTickEvent.Post, read by async gauges
    private static volatile double latestTps = 20.0;
    private static volatile double latestMspt = 0.0;
    private static volatile long onlinePlayers = 0;
    private static volatile long loadedEntities = 0;
    private static volatile long loadedChunks = 0;

    private Telemetry() {}

    public static synchronized void init(Map<String, String> resource) {
        if (tracer != null) return;
        final String base = "http://127.0.0.1:4318";

        // identify this server in the backend (service.name, mc.version, loader, ...)
        AttributesBuilder rb = Attributes.builder();
        if (resource != null) resource.forEach(rb::put);
        Resource res = Resource.getDefault().merge(Resource.create(rb.build()));

        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .setResource(res)
                .addSpanProcessor(SimpleSpanProcessor.create(
                        OtlpHttpSpanExporter.builder().setEndpoint(base + "/v1/traces").build()))
                .build();

        SdkMeterProvider meterProvider = SdkMeterProvider.builder()
                .setResource(res)
                .registerMetricReader(PeriodicMetricReader.builder(
                                OtlpHttpMetricExporter.builder().setEndpoint(base + "/v1/metrics").build())
                        .setInterval(Duration.ofSeconds(10))
                        .build())
                .build();

        OpenTelemetry otel = OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .setMeterProvider(meterProvider)
                .build();

        tracer = otel.getTracer("serverscope");
        Meter meter = otel.getMeter("serverscope");

        eventCounter = meter.counterBuilder("serverscope.events")
                .setDescription("server events (discrete + high-frequency), tagged by event/type")
                .build();

        meter.gaugeBuilder("serverscope.tps").setUnit("tps")
                .buildWithCallback(m -> m.record(latestTps));
        meter.gaugeBuilder("serverscope.mspt").setUnit("ms")
                .buildWithCallback(m -> m.record(latestMspt));
        meter.gaugeBuilder("serverscope.players").ofLongs()
                .buildWithCallback(m -> m.record(onlinePlayers));
        meter.gaugeBuilder("serverscope.entities").ofLongs()
                .buildWithCallback(m -> m.record(loadedEntities));
        meter.gaugeBuilder("serverscope.chunks").ofLongs()
                .buildWithCallback(m -> m.record(loadedChunks));

        // JVM health — heap pressure + GC are the first thing to watch when a server
        // starts to lag or OOMs; read live from java.lang.management (no game state).
        MemoryMXBean memBean = ManagementFactory.getMemoryMXBean();
        meter.gaugeBuilder("serverscope.jvm.heap.used").setUnit("By").ofLongs()
                .buildWithCallback(m -> m.record(memBean.getHeapMemoryUsage().getUsed()));
        meter.gaugeBuilder("serverscope.jvm.heap.committed").setUnit("By").ofLongs()
                .buildWithCallback(m -> m.record(memBean.getHeapMemoryUsage().getCommitted()));
        meter.gaugeBuilder("serverscope.jvm.heap.max").setUnit("By").ofLongs()
                .buildWithCallback(m -> m.record(memBean.getHeapMemoryUsage().getMax()));
        // GC counters are monotonic totals across all collectors; dashboard derives the rate
        meter.gaugeBuilder("serverscope.jvm.gc.count").ofLongs()
                .buildWithCallback(m -> m.record(gcTotal(true)));
        meter.gaugeBuilder("serverscope.jvm.gc.time").setUnit("ms").ofLongs()
                .buildWithCallback(m -> m.record(gcTotal(false)));

        event("server.starting");
    }

    /** discrete event -> span + counter */
    public static void event(String name) {
        if (tracer != null) tracer.spanBuilder(name).startSpan().end();
        if (eventCounter != null) eventCounter.add(1, Attributes.of(EVENT, name));
    }

    /**
     * discrete event with rich detail. The span carries every {@code kv} pair (full
     * detail, queryable in traces); the counter carries only the event name plus an
     * optional low-cardinality {@code metricType} label, so high-cardinality values
     * (names, ids, coords, free text) never reach the metrics backend. {@code kv} is a
     * flat key,value,key,value... list; null keys or values are skipped.
     */
    public static void event(String name, String metricType, String... kv) {
        if (tracer != null) {
            var span = tracer.spanBuilder(name);
            for (int i = 0; i + 1 < kv.length; i += 2) {
                if (kv[i] != null && kv[i + 1] != null) span.setAttribute(kv[i], kv[i + 1]);
            }
            span.startSpan().end();
        }
        if (eventCounter != null) {
            AttributesBuilder b = Attributes.builder().put(EVENT, name);
            if (metricType != null) b.put(TYPE, metricType);
            eventCounter.add(1, b.build());
        }
    }

    /** high-frequency event -> counter only (no span), tagged by type */
    public static void count(String name, String type) {
        if (eventCounter != null) {
            eventCounter.add(1, Attributes.of(EVENT, name, TYPE, type));
        }
    }

    /** gauge samples, called once per second from ServerTickEvent.Post */
    public static void sampleTick(double tps, double mspt) {
        latestTps = tps;
        latestMspt = mspt;
    }

    public static void sampleCounts(long players, long entities, long chunks) {
        onlinePlayers = players;
        loadedEntities = entities;
        loadedChunks = chunks;
    }

    /** sum GC invocation count ({@code count=true}) or accumulated pause time in ms across all collectors */
    private static long gcTotal(boolean count) {
        long total = 0;
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            long v = count ? gc.getCollectionCount() : gc.getCollectionTime();
            if (v > 0) total += v; // -1 = unsupported by this collector
        }
        return total;
    }
}
