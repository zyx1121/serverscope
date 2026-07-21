# serverscope

> Know your modded server crashed, and why, before a player has to tell you.

`neoforge` · `opentelemetry` · `otlp` · `observability`

[![Build](https://github.com/zyx1121/serverscope/actions/workflows/build.yml/badge.svg)](https://github.com/zyx1121/serverscope/actions) &nbsp;[![NeoForge](https://img.shields.io/badge/neoforge-1.21.1-111111)](gradle.properties) &nbsp;[![License: Apache 2.0](https://img.shields.io/badge/license-Apache--2.0-blue)](#license)

```
2026-06-23T02:15:07.442Z info Span #0
    Name: player.death
    Attributes:
         -> player.name: Str(Loki)
         -> cause: Str(mob)
         -> killer: Str(minecraft:zombie)
         -> weapon: Str(minecraft:diamond_sword)

2026-06-23T02:15:10.001Z info Metric #0
    Descriptor -> Name: serverscope.tps        DataPoint: 19.8
    Descriptor -> Name: serverscope.mspt       DataPoint: 12.4
    Descriptor -> Name: serverscope.events     Attributes: event(block.break), type(minecraft:stone)  DataPoint: 214
```

<sub>Real signal shapes from `core/Telemetry.java`, as an OTLP receiver sees them: spans carry full detail, counters and gauges stay low-cardinality.</sub>

Running a modded server for a small group means the first sign something's wrong is usually a Discord message asking why the server is laggy, or worse, why it's down. serverscope was built for one real NeoForge 1.21.1 "All the Mons" server: it turns every tick, join, death, command, and crash into OpenTelemetry traces and metrics, so a dashboard answers the question before anyone has to ask it.

## Install

```bash
./gradlew build        # compile + shadowJar -> build/libs/serverscope-1.0.0-all.jar
cp build/libs/serverscope-1.0.0-all.jar path/to/server/mods/
```

Run an OpenTelemetry Collector with an `otlp` receiver at `http://127.0.0.1:4318`: that's the only endpoint the mod talks to. Start the server and telemetry kicks in on `ServerStartingEvent`, no further wiring needed.

> [!NOTE]
> Needs JDK 21 (NeoForge 21.1.x, targets Minecraft 1.21.1). No prebuilt release yet: every install builds from source.
> The endpoint is hardcoded, not config: to retarget it, edit the `base` constant in `core/Telemetry.java` and rebuild.
> `./gradlew runServer` spins up a headless dev server for local testing.

## What it gives you

- **Emits real OTel signals**: spans for lifecycle, player, chat, command, advancement, and death events; counters for high-frequency block, mob, damage, and stat events; gauges for TPS, MSPT, players, entities, chunks.
- **Watches JVM health**: heap used/committed/max and GC count/time straight from `java.lang.management`, so heap pressure shows up before an OOM does.
- **Flags unclean restarts**: on boot, compares a state file against the last run and emits `server.unclean_restart` (with the crash report's description, when one exists).
- **Ships OTLP/HTTP with no autoconfigure**: a hand-built `SdkTracerProvider` / `SdkMeterProvider`, no ServiceLoader surprises.
- **Stays modular**: platform-agnostic `core` + a NeoForge `adapter`, ready to fan out to Fabric or newer MC versions.

## Signals

| Layer | Signal | Events |
|---|---|---|
| Discrete (low-freq) | **span** | server lifecycle (starting/started/stopping/stopped) · unclean restart · player join/leave/respawn/dimension · chat · command · advancement · death |
| High-frequency | **counter** | block break/place · mob spawn/death · damage · interact · vanilla stat awards |
| Sampled (1/sec) | **gauge** | TPS · MSPT · online players · loaded entities · loaded chunks · JVM heap used/committed/max · GC count/time |

High-cardinality values (player names, coordinates, crash reasons, chat/command text) only ever land on spans; counter and gauge labels stay low-cardinality (event name plus a bounded `type`), so the metrics backend never blows up.

## Crash detection

On `ServerStartingEvent`, the NeoForge adapter checks a `serverscope-state.txt` file it wrote on the previous boot. If it still says `running`, the previous run never shut down cleanly (watchdog hang, OOM, `SIGKILL`), and serverscope emits `server.unclean_restart` with the newest `crash-reports/` file's `Description:` line attached, or nothing if the JVM died before writing one.

## Tech stack

| Layer | Choice |
|---|---|
| Loader | NeoForge 21.1.x (Minecraft 1.21.1), Mojmap + Parchment mappings |
| Telemetry | OpenTelemetry Java SDK 1.62, hand-built SDK providers (no autoconfigure) |
| Transport | OTLP/HTTP via the JDK HTTP sender, not okhttp (avoids pulling in `kotlin-stdlib`, which clashes with Kotlin mods on NeoForge's module system) |
| Packaging | Shadow with a dedicated `shadowMe` configuration: bundles OTel and its transitives only, never Minecraft/NeoForge |

## Layout

```
src/main/java/tw/zyx/serverscope/
  core/Telemetry.java       # platform-agnostic: OTel SDK, span/counter/gauge API
  neoforge/ServerScope.java # NeoForge adapter: event hooks -> core
```

## Contributing

Issues and PRs welcome: ground rules in [CONTRIBUTING.md](https://github.com/zyx1121/.github/blob/main/CONTRIBUTING.md).

## License

[Apache-2.0](LICENSE) · built to catch the next 3am OOM before the "All the Mons" players do.
