# Container AUT — `stove-container`

Use `stove-container` when the application under test should run as a Docker image. Works for **any language** — Go, Python, Node.js, Rust, .NET, JVM, anything that can ship in a container. Same Stove DSL, same systems, same envMapper / argsMapper model — only the runner changes.

If you want fast iteration without an image, use `stove-process` (`processApp` / `goApp`). See [other-languages.md](other-languages.md).

## Image source: not Stove's job

`containerApp(...)` only needs an **image reference**. Where that image comes from is up to the user / CI:

- **Pre-built in CI** — most common. CI publishes an image tag (e.g. `ghcr.io/acme/app:sha-abc123`); the test reads it from a system property or env var.
- **Pulled from a registry** — Testcontainers handles the pull lazily.
- **Locally built** — optionally wire a Gradle `Exec` task (`docker build`) and `dependsOn` it from your test task. This is one valid path, not a requirement.

Lead with the pre-built path. Show local-build as an optional convenience. Never frame "Stove builds your image" — Stove launches images, it does not own the build pipeline.

## When to recommend container mode

| Need | Use |
|------|-----|
| Fastest local iteration loop | `stove-process` |
| CI parity with the production image | `stove-container` |
| Catch glibc/musl, base image, locale, CA cert regressions | `stove-container` |
| Inner debug loop, breakpoints in IDE | `stove-process` |
| One repo runs both modes, branched on a system property | Both — single StoveConfig |

A common pattern: `e2eTest` uses process mode for local development; `e2eTest-container` runs container mode in CI before merge using the image CI just built and tagged.

## Setup checklist

```
- [ ] Step 1: Add stove-container dependency
- [ ] Step 2: Decide image source (CI artifact, registry pull, or optional local build)
- [ ] Step 3: Add an e2eTest-container Test task; pass the image tag as a system property
- [ ] Step 4: Wire containerApp(...) into StoveConfig
- [ ] Step 5: Pick a networking model (host network or port binding)
- [ ] Step 6: (Optional) Bind-mount data / coverage directories
```

## Step 1: Dependency

```kotlin
dependencies {
    testImplementation(platform("com.trendyol:stove-bom:$stoveVersion"))
    testImplementation("com.trendyol:stove-container")
    // ... other stove dependencies as needed
}
```

## Step 2 + 3: Image source and Gradle wiring

The default and recommended pattern: CI (or another build step) produces an image tag, and the test task receives it via a system property.

```kotlin title="build.gradle.kts"
val containerImage = providers.environmentVariable("APP_IMAGE")
    .orElse(providers.gradleProperty("app.image"))
    .orElse("my-app:local")           // local fallback only

tasks.register<Test>("e2eTest-container") {
    useJUnitPlatform()
    systemProperty("app.container.image", containerImage.get())
}
```

If you also want a Gradle-driven local build (optional), add an `Exec` task and depend on it explicitly:

```kotlin
val dockerExecutable = providers.environmentVariable("DOCKER_EXECUTABLE").getOrElse("docker")

tasks.register<Exec>("buildContainerImage") {
    description = "Optional convenience: builds the AUT image locally."
    commandLine(
        dockerExecutable, "build",
        "--file", projectDir.resolve("Dockerfile").absolutePath,
        "--tag", "my-app:local",
        projectDir.absolutePath
    )
    outputs.upToDateWhen { false }
}

// Only depend on it for the local-build path:
tasks.named<Test>("e2eTest-container-local") {
    dependsOn("buildContainerImage")
    systemProperty("app.container.image", "my-app:local")
}
```

The CI path uses the image already produced by the upstream build; the local path opts into building. The Stove test code does not change.

## Step 4: StoveConfig

```kotlin
import com.trendyol.stove.container.ContainerTarget
import com.trendyol.stove.container.containerApp
import com.trendyol.stove.system.application.envMapper

containerApp(
    image = System.getProperty("app.container.image")
        ?: error("app.container.image system property not set"),
    target = ContainerTarget.Server(
        hostPort = 8090,
        internalPort = 8090,
        portEnvVar = "APP_PORT",
        bindHostPort = false      // host network → no need to bind
    ),
    envProvider = envMapper {
        "database.host" to "DB_HOST"
        "database.port" to "DB_PORT"
        "database.name" to "DB_NAME"
        "kafka.bootstrapServers" to "KAFKA_BROKERS"
        env("OTEL_EXPORTER_OTLP_ENDPOINT", "localhost:4317")
    },
    configureContainer = {
        withNetworkMode("host")
        // bind mounts, log consumers, capabilities — anything Testcontainers exposes
    },
    beforeStarted = { configurations ->
        // optional pre-start hook with resolved configs
    }
)
```

### `containerApp` parameters

| Parameter | Purpose |
|-----------|---------|
| `image` | Image reference, e.g. `ghcr.io/acme/app:sha-abc` or `my-app:local` |
| `target` | `ContainerTarget.Server` or `ContainerTarget.Worker` (carries readiness) |
| `registry` | Image registry override (defaults to `DEFAULT_REGISTRY`) |
| `compatibleSubstitute` | Substitute image for arch/OS compatibility |
| `command` | Override container command (appended with argsMapper output) |
| `envProvider` | `envMapper { ... }` mapping Stove configs to env vars |
| `argsProvider` | `argsMapper(prefix, separator) { ... }` for CLI-flag-driven apps |
| `beforeStarted` | Async hook with resolved configs, runs before container start |
| `configureContainer` | `GenericContainer<*>.()` — bind mounts, network mode, etc. |
| `gracefulShutdownTimeout` | Defaults to 5 seconds |

### `ContainerTarget` variants

| Variant | Use case | Default readiness |
|---------|----------|-------------------|
| `ContainerTarget.Server(hostPort, internalPort, portEnvVar, bindHostPort)` | HTTP / gRPC / TCP servers | HTTP GET `http://localhost:$hostPort/health` |
| `ContainerTarget.Worker()` | Kafka consumers, batch jobs | 2-second fixed delay |

## Step 5: Networking strategies

**Host network (Linux only)** — container shares the host network namespace. Reach Postgres / Kafka on `localhost`. Set `bindHostPort = false`:

```kotlin
target = ContainerTarget.Server(hostPort = 8090, internalPort = 8090, portEnvVar = "APP_PORT", bindHostPort = false),
configureContainer = { withNetworkMode("host") }
```

**Port binding (cross-platform)** — Stove binds `hostPort → internalPort`. The app must reach databases / brokers via shared network aliases or `host.docker.internal`:

```kotlin
target = ContainerTarget.Server(hostPort = 8090, internalPort = 8090, portEnvVar = "APP_PORT", bindHostPort = true),
configureContainer = { withNetwork(Network.SHARED) }
```

Docker Desktop on macOS / Windows does **not** support host networking — use port binding there.

## Step 6: Bind mounts (optional)

Use for any data the container or the test needs to share with the host: coverage directories, fixture seeds, read-only configs, etc. Anything Testcontainers exposes is available inside `configureContainer`.

```kotlin
configureContainer = {
    withFileSystemBind(hostDir, "/inside/container")
    withLogConsumer(Slf4jLogConsumer(LoggerFactory.getLogger("app")))
}
```

For Go integration coverage specifically, see [go-setup.md](go-setup.md#code-coverage).

## Running

```bash
# CI/registry image — image tag passed in
./gradlew e2eTest-container -Papp.image=ghcr.io/acme/app:sha-abc123
# or
APP_IMAGE=ghcr.io/acme/app:sha-abc123 ./gradlew e2eTest-container

# Optional local-build path (only if you wired buildContainerImage)
./gradlew e2eTest-container-local
```

## Single StoveConfig, both modes

The recipe pattern: branch on a system property to switch between starters within one config file.

```kotlin
when (resolveAutMode()) {
    AutMode.Process -> processApp { /* ... */ }
    AutMode.Container -> containerApp(/* ... */)
}

private fun resolveAutMode(): AutMode =
    when ((System.getProperty("aut.mode") ?: "process").lowercase()) {
        "process" -> AutMode.Process
        "container" -> AutMode.Container
        else -> error("Unsupported aut.mode")
    }
```

Drive the choice from Gradle:

```kotlin
tasks.register<Test>("e2eTest") { systemProperty("aut.mode", "process") /* ... */ }
tasks.register<Test>("e2eTest-container") { systemProperty("aut.mode", "container") /* ... */ }
```

## Common pitfalls

| Symptom | Cause | Fix |
|---------|-------|-----|
| `connection refused` to Postgres / Kafka inside container | Container can't reach Testcontainers on `localhost` | `withNetworkMode("host")` (Linux) or shared network + aliases |
| Stove never sees `/health` | Wrong port / binding | Confirm `bindHostPort` matches network mode; verify app listens on `internalPort` |
| `Failed to start container application` | Image missing or unauthorized pull | Verify the image exists locally / in the registry; check `docker images` and registry credentials |
| Slow inner loop | Image build dominates | Use `stove-process` for daily dev; container mode in CI |

## Reference

- Module source: `starters/container/stove-container/`
- DSL: `starters/container/stove-container/src/main/kotlin/com/trendyol/stove/container/ContainerDsl.kt`
- Showcase (process + container in one repo): `recipes/process/golang/go-showcase/`
- Docs: `docs/other-languages/go-container.md` (Go-specific walkthrough)
