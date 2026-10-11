# serverscope

NeoForge 1.21.1 server mod that sends OpenTelemetry traces and metrics over OTLP/HTTP. `core/Telemetry.java` is platform-agnostic. `neoforge/ServerScope.java` maps NeoForge events to it. Read [README.md](README.md) first.

## Rules

- `core` must not import Minecraft or NeoForge classes, so a Fabric or newer adapter can reuse it.
- High-cardinality values (player names, coordinates, crash reasons, chat and command text) go only on spans. Counter and gauge labels stay low-cardinality: an event name and a bounded `type`.
- Build the SDK providers by hand. Do not add OpenTelemetry autoconfigure.
- Use the JDK HTTP sender. Do not add okhttp, because its `kotlin-stdlib` clashes with Kotlin mods.
- Bundle only OpenTelemetry and its dependencies through `shadowMe`. Never shade Minecraft or NeoForge.
- Repository documents and code comments are English.

## Verify

```sh
./gradlew build
./gradlew runServer
```

Run the build on the sandbox with JDK 21. With a collector on `127.0.0.1:4318`, `runServer` must produce `server.starting` and `server.started` spans.
