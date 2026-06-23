```
 ___  ___ _ ____   _____ _ __ ___  ___ ___  _ __   ___
/ __|/ _ \ '__\ \ / / _ \ '__/ __|/ __/ _ \| '_ \ / _ \
\__ \  __/ |   \ V /  __/ |  \__ \ (_| (_) | |_) |  __/
|___/\___|_|    \_/ \___|_|  |___/\___\___/| .__/ \___|
                                           |_|
```

# serverscope

A server-side observability mod for modded Minecraft. Subscribes to server game
events and exports **OpenTelemetry** (traces + metrics) over OTLP — live
TPS/MSPT, player/entity/chunk gauges, and a full event feed — to any OTLP
backend. Built for a real NeoForge 1.21.1 "All the Mons" server, feeding a local
OpenTelemetry Collector → a self-built dashboard.

## Tech Stack

- **Loader**: NeoForge 21.1.x (Minecraft 1.21.1), Mojmap + Parchment
- **Telemetry**: OpenTelemetry Java SDK 1.62 — hand-built `SdkTracerProvider` + `SdkMeterProvider`, no autoconfigure (so no ServiceLoader surprises)
- **Transport**: OTLP/HTTP via the **JDK HTTP sender** — deliberately *not* okhttp, which drags in `kotlin-stdlib` and clashes with Kotlin mods on NeoForge's Java Module System
- **Packaging**: shadow with a dedicated `shadowMe` configuration — bundles OTel + transitives only, never Minecraft/NeoForge
- **Architecture**: platform-agnostic `core` + per-loader `adapter`, ready to fan out to Fabric / newer MC versions via Architectury + Stonecutter

## How it works

The NeoForge adapter subscribes to server-side events on `NeoForge.EVENT_BUS`
and hands them to the platform-agnostic core, which emits three OTel signal
types:

| Layer | Signal | Events |
|---|---|---|
| Discrete (low-freq) | **span** | server lifecycle · player join/leave/respawn/dimension · chat · command · advancement · death |
| High-frequency | **counter** | block break/place · mob spawn · interact · damage (tagged by type) |
| Sampled (1/sec, `ServerTickEvent.Post`) | **gauge** | TPS · MSPT · online players · loaded entities · loaded chunks |

MSPT is read straight from `MinecraftServer.getCurrentSmoothedTickTime()`; TPS is
derived from it. Everything ships to a local OpenTelemetry Collector, which can
fan out to a self-built dashboard, Grafana, or any OTLP-compatible backend — the
mod never needs to know where the data ends up.

## Build

```bash
./gradlew build       # compile + shadowJar  ->  build/libs/serverscope-<ver>-all.jar
./gradlew runServer   # headless dev server
```

Deploy: drop the `-all.jar` into the server's `mods/`. It exports OTLP to
`http://127.0.0.1:4318` by default (edit `core/Telemetry.java` to retarget) —
run an OpenTelemetry Collector there with an `otlp` receiver.

## Layout

```
src/main/java/tw/zyx/serverscope/
  core/Telemetry.java       # platform-agnostic: OTel SDK, span/counter/gauge API
  neoforge/ServerScope.java # NeoForge adapter: @SubscribeEvent hooks -> core
```

## License

Apache-2.0
