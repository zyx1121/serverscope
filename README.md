<div align="center">

<h1>serverscope</h1>

<p><strong>Know that your modded Minecraft server crashed, and why, before a player tells you.</strong></p>

[![Build](https://img.shields.io/github/actions/workflow/status/zyx1121/serverscope/build.yml?branch=main&style=flat-square&label=build)](https://github.com/zyx1121/serverscope/actions)
[![NeoForge](https://img.shields.io/badge/NeoForge-1.21.1-DF6E1E?style=flat-square)](gradle.properties)
[![OpenTelemetry](https://img.shields.io/badge/OpenTelemetry-OTLP-425CC7?style=flat-square&logo=opentelemetry&logoColor=white)](src/main/java/tw/zyx/serverscope/core/Telemetry.java)
[![License](https://img.shields.io/github/license/zyx1121/serverscope?style=flat-square)](LICENSE)

**English** | [繁體中文](README.zh-TW.md) | [简体中文](README.zh-CN.md)

</div>

On a small modded server, the first sign of trouble is often a chat message: why is the server slow, or why is it down? serverscope turns each tick, join, death, command and crash into OpenTelemetry traces and metrics. Then a dashboard answers before anyone asks. It runs on one real NeoForge 1.21.1 "All the Mons" server.

## Features

- **OpenTelemetry signals**: spans for lifecycle, player, chat, command, advancement and death events. Counters for frequent events, and gauges for TPS, MSPT, players, entities and chunks.
- **JVM health**: heap and GC numbers from `java.lang.management`, so heap pressure shows before an out-of-memory crash.
- **Unclean restart alerts**: at boot, the mod compares a state file with the last run and emits `server.unclean_restart`. It adds the crash report description when one exists.
- **OTLP over HTTP, no autoconfigure**: hand-built SDK providers, with no ServiceLoader surprises.
- **Modular**: a platform-agnostic `core` and a NeoForge `adapter`.

## Quick start

```sh
./gradlew build
cp build/libs/serverscope-1.0.0-all.jar path/to/server/mods/
```

Run an OpenTelemetry Collector with an `otlp` receiver at `http://127.0.0.1:4318`. Start the server. Telemetry starts on `ServerStartingEvent`, and the collector receives spans and metrics.

## Usage

| Layer | Signal | Events |
|-------|--------|--------|
| Discrete, low frequency | span | server lifecycle, unclean restart, player join, leave, respawn and dimension change, chat, command, advancement, death |
| High frequency | counter | block break and place, mob spawn and death, damage, interact, vanilla stat awards |
| Sampled every second | gauge | TPS, MSPT, online players, loaded entities, loaded chunks, JVM heap, GC count and time |

High-cardinality values (player names, coordinates, crash reasons, chat and command text) go only on spans. Counter and gauge labels stay low-cardinality: the event name and a bounded `type`.

## Configuration

| Needs | For |
|-------|-----|
| JDK 21 | the build |
| A NeoForge 21.1.x server for Minecraft 1.21.1 | running the mod |
| An OTLP/HTTP receiver at `127.0.0.1:4318` | receiving the data |

The endpoint is a constant, `base`, in `core/Telemetry.java`. To change it, edit the constant and build again.

## How it works

- At `ServerStartingEvent`, the adapter reads `serverscope-state.txt` from the last boot. If it still says `running`, the last run did not shut down cleanly (a watchdog hang, out of memory or `SIGKILL`). The mod then emits `server.unclean_restart` with the `Description:` line of the newest file in `crash-reports/`.
- Transport uses the JDK HTTP sender, not okhttp. okhttp pulls in `kotlin-stdlib`, which clashes with Kotlin mods on the NeoForge module system.
- Shadow bundles OpenTelemetry and its dependencies through a `shadowMe` configuration, never Minecraft or NeoForge.

## Limits

- No prebuilt release. Each install builds from source.
- The endpoint is in code, not in a config file.

## For AI agents

Facts for coding agents and LLM tools. The rules for changing the code are in [AGENTS.md](AGENTS.md).

- **Summary**: NeoForge 1.21.1 server mod that sends OpenTelemetry spans, counters and gauges over OTLP/HTTP, with JVM health and unclean restart detection.
- **Type**: service
- **Interfaces**: OTLP/HTTP out to `http://127.0.0.1:4318/v1/traces` and `/v1/metrics`. State file `serverscope-state.txt`.
- **Install**: `./gradlew build`, then copy `build/libs/serverscope-1.0.0-all.jar` to the server `mods/` folder
- **Run**: start the server. `./gradlew runServer` starts a headless dev server.
- **Verify**: `./gradlew build`, then watch the collector for `server.starting` spans
- **Config**: none. The endpoint is the `base` constant in `core/Telemetry.java`.
- **Secrets**: none
- **Limits**: JDK 21, NeoForge 21.1.x, Minecraft 1.21.1. No release artifacts.

## Development

```sh
./gradlew build       # compile and shadowJar
./gradlew runServer   # headless dev server
```

`core/Telemetry.java` holds the OpenTelemetry SDK and the span, counter and gauge API. `neoforge/ServerScope.java` maps NeoForge events to `core`. CI runs `./gradlew build` on JDK 21.

## License

[Apache-2.0](LICENSE)
