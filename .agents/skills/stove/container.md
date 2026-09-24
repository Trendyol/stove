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
    group = "verification"
    testClassesDirs = sourceSets["test-e2e"].output.classesDirs
    classpath = sourceSets["test-e2e"].runtimeClasspath
    useJUnitPlatform()
    systemProperty("aut.mode", "container")
    systemProperty("app.container.image", containerImage.get())
}
```

This assumes the source set and test engine from [gradle-config.md](gradle-config.md) already exist. Reuse their actual names. If a task already exists, configure it with `tasks.named<Test>` instead of registering it again.

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
tasks.register<Test>("e2eTest-container-local") {
    group = "verification"
    testClassesDirs = sourceSets["test-e2e"].output.classesDirs
    classpath = sourceSets["test-e2e"].runtimeClasspath
    useJUnitPlatform()
    dependsOn("buildContainerImage")
    systemProperty("aut.mode", "container")
    systemProperty("app.container.image", "my-app:local")
}
```

The CI path uses the image already produced by the upstream build; the local path opts into building. The Stove test code does not change.

## Step 4: StoveConfig

Register the runner last inside the existing `Stove().with { ... }.run()` lifecycle. This minimal example publishes the AUT port; configure dependency connectivity in Step 5 before adding databases, Kafka, or tracing.

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
        bindHostPort = true
    )
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

There are two address spaces: the test JVM reaches services through host-mapped ports; the AUT container reaches them through its own network. `configureExposedConfiguration` maps values for the AUT but does not automatically translate host addresses into container addresses.

**Port binding and a shared network** — attach the AUT and every container dependency to the same network. Give each dependency an alias and pass that alias plus its internal port to the AUT. Connecting only the AUT to `Network.SHARED` is insufficient.

Example with PostgreSQL (add the Postgres and HTTP dependencies). Create `appNetwork` for the suite and close it after `Stove.stop()` in teardown:

```kotlin
val appNetwork = Network.newNetwork()

Stove().with {
    httpClient { HttpClientSystemOptions(baseUrl = "http://localhost:8090") }
    postgresql {
        PostgresqlOptions(
            databaseName = "app_test",
            container = PostgresqlContainerOptions {
                withNetwork(appNetwork)
                withNetworkAliases("app-db")
            },
            configureExposedConfiguration = { cfg ->
                listOf(
                    "database.host=app-db", "database.port=5432", "database.name=app_test",
                    "database.username=${cfg.username}", "database.password=${cfg.password}"
                )
            }
        )
    }
    containerApp(
        image = System.getProperty("app.container.image") ?: error("Missing app.container.image"),
        target = ContainerTarget.Server(hostPort = 8090, internalPort = 8090, portEnvVar = "APP_PORT"),
        envProvider = envMapper {
            "database.host" to "DB_HOST"
            "database.port" to "DB_PORT"
            "database.name" to "DB_NAME"
            "database.username" to "DB_USER"
            "database.password" to "DB_PASS"
        },
        configureContainer = { withNetwork(appNetwork) }
    )
}.run()
```

The app must bind to a container-accessible interface such as `0.0.0.0`, not just its loopback address. Stove's database client still uses the mapped host endpoint; the app receives `app-db:5432`.

**Host network** — use only when the container runtime supports and enables it and the test JVM can reach that same host network. It is common on Linux; Docker Desktop versions with optional host networking need that feature enabled. Set `bindHostPort = false` and use matching host/internal ports:

```kotlin
target = ContainerTarget.Server(hostPort = 8090, internalPort = 8090, portEnvVar = "APP_PORT", bindHostPort = false),
configureContainer = { withNetworkMode("host") }
```

**Callbacks into the test JVM** — OTLP and the Go Kafka observer usually run in the JVM's host network. Expose their actual ports using the runtime's supported host-access mechanism (for example, Testcontainers `exposeHostPorts` before the AUT starts and `host.testcontainers.internal` inside it). The host alias and receiver ports must be reachable; container `localhost` refers to the container itself. See [tracing.md](tracing.md) and [go-setup.md](go-setup.md).

**Kafka** — configure a listener/advertised listener reachable from the AUT and preserve one reachable from the test JVM. Replacing only the bootstrap hostname cannot fix unreachable addresses returned in broker metadata. Check the selected Kafka image and Testcontainers version's listener configuration.

Default readiness probes run from the test JVM at `http://localhost:$hostPort/health`. For a remote Docker host, supply a reachable readiness URL and HTTP client base URL. Disabling fixed port binding does not discover a replacement port for the HTTP client. Use distinct fixed ports or serialized suites when tests share a host.

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

private enum class AutMode { Process, Container }

private fun resolveAutMode(): AutMode =
    when ((System.getProperty("aut.mode") ?: "process").lowercase()) {
        "process" -> AutMode.Process
        "container" -> AutMode.Container
        else -> error("Unsupported aut.mode")
    }
```

Drive the choice from Gradle:

```kotlin
tasks.named<Test>("e2eTest") { systemProperty("aut.mode", "process") }
tasks.named<Test>("e2eTest-container") { systemProperty("aut.mode", "container") }
```

## Common pitfalls

| Symptom | Cause | Fix |
|---------|-------|-----|
| `connection refused` to Postgres / Kafka inside container | Container can't reach dependencies at JVM addresses | Shared network on both sides with aliases/internal ports, or supported host networking; verify Kafka advertised listeners |
| Stove never sees `/health` | Wrong port / binding | Confirm `bindHostPort` matches network mode; verify app listens on `internalPort` |
| `Failed to start container application` | Image missing or unauthorized pull | Verify the image exists locally / in the registry; check `docker images` and registry credentials |
| Slow inner loop | Image build dominates | Use `stove-process` for daily dev; container mode in CI |

## Reference

- Module source: `starters/container/stove-container/`
- DSL: `starters/container/stove-container/src/main/kotlin/com/trendyol/stove/container/ContainerDsl.kt`
- Showcase (process + container in one repo): `recipes/process/golang/go-showcase/`
- Docs: `docs/other-languages/go-container.md` (Go-specific walkthrough)
