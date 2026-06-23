package tw.zyx.serverscope.core;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.exporter.otlp.http.metrics.OtlpHttpMetricExporter;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;

import java.time.Duration;

/**
 * Platform-agnostic telemetry core. Hand-built OpenTelemetry SDK (no autoconfigure
 * -> no ServiceLoader surprises), OTLP/HTTP over the JDK sender (no okhttp/kotlin).
 *
 * Three signal types:
 *   - event(name[,attr]) : discrete low-freq -> span + counted
 *   - count(name, type)  : high-freq         -> counter only (no span flood)
 *   - sampleTick/Counts  : gauges            -> async, read latest sampled values
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

    public static synchronized void init() {
        if (tracer != null) return;
        final String base = "http://127.0.0.1:4318";

        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(
                        OtlpHttpSpanExporter.builder().setEndpoint(base + "/v1/traces").build()))
                .build();

        SdkMeterProvider meterProvider = SdkMeterProvider.builder()
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

        event("server.starting");
    }

    /** discrete event -> span + counter */
    public static void event(String name) {
        if (tracer != null) tracer.spanBuilder(name).startSpan().end();
        if (eventCounter != null) eventCounter.add(1, Attributes.of(EVENT, name));
    }

    /** discrete event with one attribute -> span (attributed) + counter */
    public static void event(String name, String attrKey, String attrVal) {
        if (tracer != null) {
            tracer.spanBuilder(name).setAttribute(attrKey, attrVal).startSpan().end();
        }
        if (eventCounter != null) {
            eventCounter.add(1, Attributes.of(EVENT, name, AttributeKey.stringKey(attrKey), attrVal));
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
}
