# Container AUT (`stove-container`)

`stove-container` runs the application under test as a Docker image. It works with **any language and any framework** — Go, Python, Node.js, Rust, .NET, JVM, anything that ships in a container — and gives you image-level parity with what you deploy to production.

For a host-binary AUT (process mode), see [`stove-process`](../other-languages/index.md). For a Go-specific walkthrough that pairs `stove-container` with PostgreSQL + Kafka + tracing + coverage, see [Go Container Mode](../other-languages/go-container.md).

## What `stove-container` is responsible for

- Pulling / locating the image, configuring it as a Testcontainers `GenericContainer`
- Mapping Stove configurations to environment variables (`envMapper`) or CLI arguments (`argsMapper`)
- Optional pre-start hook (`beforeStarted`) with resolved configurations
- Container start, readiness check, log streaming
- Graceful stop with configurable timeout, force-close fallback

## What `stove-container` is **not** responsible for

- **Building the image.** That is the user's pipeline. Stove only needs an image reference.
- **Choosing the image registry or auth.** Use Testcontainers / Docker config like you would for any other test.
- **Owning the Dockerfile.** Show your existing production Dockerfile to Stove via a tag.

## Install

```kotlin
dependencies {
    testImplementation(platform("com.trendyol:stove-bom:$stoveVersion"))
    testImplementation("com.trendyol:stove-container")
}
```

## Image source patterns

`containerApp(...)` only needs an image reference. Where it comes from is your choice:

| Pattern | When to use | How |
|---------|-------------|-----|
| **CI artifact** | Most realistic CI path | CI publishes a tag (e.g. `ghcr.io/acme/app:sha-abc`); test reads it from a system property or env var |
| **Registry pull** | Image already published; no local build needed | Just reference the tag — Testcontainers pulls lazily on first use |
| **Local build** (optional) | Inner-loop convenience when iterating on the Dockerfile | Wire a Gradle `Exec` task running `docker build`; have a *separate* test task `dependsOn` it |

The minimal Gradle wiring for the CI path:

```kotlin title="build.gradle.kts"
val containerImage = providers.environmentVariable("APP_IMAGE")
    .orElse(providers.gradleProperty("app.image"))
    .orElse("my-app:local")     // local fallback only

tasks.register<Test>("e2eTest-container") {
    useJUnitPlatform()
    systemProperty("app.container.image", containerImage.get())
}
```

```bash
# CI
APP_IMAGE=ghcr.io/acme/app:sha-abc123 ./gradlew e2eTest-container
# or
./gradlew e2eTest-container -Papp.image=ghcr.io/acme/app:sha-abc123
```

A separate optional task can wrap `docker build` for local convenience without coupling it to the main test task.

## DSL: `containerApp(...)`

```kotlin
import com.trendyol.stove.container.ContainerTarget
import com.trendyol.stove.container.containerApp
import com.trendyol.stove.system.application.envMapper

containerApp(
    image = System.getProperty("app.container.image"),
    target = ContainerTarget.Server(
        hostPort = 8090,
        internalPort = 8090,
        portEnvVar = "APP_PORT",
        bindHostPort = false      // host network → no need to bind
    ),
    envProvider = envMapper {
        "database.host" to "DB_HOST"
        "database.port" to "DB_PORT"
        "kafka.bootstrapServers" to "KAFKA_BROKERS"
        env("OTEL_EXPORTER_OTLP_ENDPOINT", "localhost:4317")
    },
    configureContainer = {
        withNetworkMode("host")
    },
    beforeStarted = { configurations ->
        // optional async hook with resolved configs
    }
)
```

### Parameters

| Parameter | Type | Purpose |
|-----------|------|---------|
| `image` | `String` | Image reference. From CI tag, registry, or local build — Stove does not care |
| `target` | `ContainerTarget` | `Server` (HTTP / gRPC / TCP) or `Worker` (consumers, jobs); carries the readiness strategy |
| `registry` | `String` | Image registry override (defaults to `DEFAULT_REGISTRY`) |
| `compatibleSubstitute` | `String?` | Substitute image for arch/OS compatibility (Apple Silicon / arm64) |
| `command` | `List<String>` | Override container command (gets `argsMapper` output appended) |
| `envProvider` | `EnvProvider` | `envMapper { ... }` mapping Stove configs to env vars |
| `argsProvider` | `ArgsProvider` | `argsMapper(prefix, separator) { ... }` for CLI-flag-driven apps |
| `beforeStarted` | suspend lambda | Async hook with resolved configs, runs before container start |
| `configureContainer` | `GenericContainer<*>.()` | Anything Testcontainers exposes — bind mounts, network mode, capabilities, log consumers |
| `gracefulShutdownTimeout` | `Duration` | Defaults to 5 seconds; falls back to force-close on timeout |

### `ContainerTarget` variants

| Variant | Use case | Default readiness |
|---------|----------|-------------------|
| `ContainerTarget.Server(hostPort, internalPort, portEnvVar, bindHostPort)` | HTTP / gRPC / TCP servers | HTTP GET `http://localhost:$hostPort/health` |
| `ContainerTarget.Worker()` | Kafka consumers, batch jobs | 2-second fixed delay |

`bindHostPort = false` is the right default when using `withNetworkMode("host")` — the container shares the host network namespace and binding the port again would conflict.

### Readiness strategies

`ContainerTarget.Server` defaults to `ReadinessStrategy.HttpGet`. You can override:

```kotlin
target = ContainerTarget.Server(
    hostPort = 8090,
    internalPort = 8090,
    portEnvVar = "APP_PORT",
    readiness = ReadinessStrategy.TcpPort(8090)   // for raw TCP / gRPC w/o HTTP
)
```

| Strategy | Use case |
|----------|----------|
| `ReadinessStrategy.HttpGet(url, timeout, retries, retryDelay, expectedStatusCodes)` | REST APIs |
| `ReadinessStrategy.TcpPort(port)` | gRPC / raw TCP (no HTTP) |
| `ReadinessStrategy.Probe { ... }` | Custom (file, DB query, log scan, etc.) |
| `ReadinessStrategy.FixedDelay(duration)` | Workers / no readiness signal |

## Networking strategies

=== "Host network (Linux only)"

    ```kotlin
    target = ContainerTarget.Server(hostPort = 8090, internalPort = 8090,
        portEnvVar = "APP_PORT", bindHostPort = false),
    configureContainer = { withNetworkMode("host") }
    ```

    Container shares the host's network namespace. The app reaches PostgreSQL / Kafka on `localhost`. Does **not** work on Docker Desktop for macOS / Windows.

=== "Port binding (cross-platform)"

    ```kotlin
    target = ContainerTarget.Server(hostPort = 8090, internalPort = 8090,
        portEnvVar = "APP_PORT", bindHostPort = true),
    configureContainer = { withNetwork(Network.SHARED) }
    ```

    Stove binds `hostPort → internalPort`. The app reaches databases / brokers via shared network aliases or `host.docker.internal`.

## `configureContainer { ... }`

Accepts a `GenericContainer<*>.()` block. Anything Testcontainers exposes is available:

```kotlin
configureContainer = {
    withNetworkMode("host")
    withFileSystemBind(hostPath, "/inside/container")
    withLogConsumer(Slf4jLogConsumer(LoggerFactory.getLogger("app")))
    withEnv("EXTRA_DEBUG", "1")
    withCreateContainerCmdModifier { cmd -> /* low-level docker-java */ }
}
```

Use bind mounts for any data the container or the test needs to share with the host: coverage directories, fixture seeds, read-only configs.

## `beforeStarted { ... }`

Async hook that runs after Stove resolves all configurations but before the container starts. Useful for prepping data the app expects on boot.

```kotlin
beforeStarted = { configurations ->
    seedRedisCache(configurations["redis.host"]!!)
}
```

## Switching between process and container mode

A single `StoveConfig.kt` can serve both starters by branching on a system property. Infrastructure systems and tests stay identical — only the AUT runner changes.

```kotlin
when ((System.getProperty("aut.mode") ?: "process").lowercase()) {
    "process"   -> processApp { ProcessApplicationOptions(/* ... */) }
    "container" -> containerApp(/* ... */)
    else        -> error("Unsupported aut.mode")
}
```

```kotlin
tasks.register<Test>("e2eTest")           { systemProperty("aut.mode", "process") }
tasks.register<Test>("e2eTest-container") { systemProperty("aut.mode", "container") }
```

A common pattern: `e2eTest` runs process mode locally for fast iteration; `e2eTest-container` runs container mode in CI against the image the build job just published.

## Common pitfalls

| Symptom | Cause | Fix |
|---------|-------|-----|
| `connection refused` to Postgres / Kafka inside container | Container can't reach Testcontainers on `localhost` | `withNetworkMode("host")` (Linux) or shared network + aliases (cross-platform) |
| Stove never sees `/health` | Wrong port / binding | Confirm `bindHostPort` matches network mode; verify app listens on `internalPort` |
| `Failed to start container application` | Image missing or unauthorized pull | Verify the image exists locally / in the registry; check `docker images` and registry credentials |
| Slow inner loop | Image build dominates iteration | Use [`stove-process`](../other-languages/index.md) for daily dev; container mode in CI |
| App killed before clean shutdown | `gracefulShutdownTimeout` too short for the app | Bump `gracefulShutdownTimeout` on `containerApp(...)` |

## Reference

- Module source: `starters/container/stove-container/`
- DSL source: `starters/container/stove-container/src/main/kotlin/com/trendyol/stove/container/ContainerDsl.kt`
- Go-specific recipe (process **and** container modes in one repo): [`recipes/process/golang/go-showcase`](https://github.com/Trendyol/stove/tree/main/recipes/process/golang/go-showcase)
- Related docs:
  - [Go Container Mode](../other-languages/go-container.md) — Go-specific walkthrough that uses this module
  - [Other Languages & Stacks](../other-languages/index.md) — process vs. container overview
  - [Dashboard](18-dashboard.md) and [MCP](21-mcp.md) — observability for any AUT, including container ones
  - [Tracing](15-tracing.md) — distributed tracing across the test and the container
