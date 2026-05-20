# Go · Container Mode

Run the Go app as a Docker image instead of a host binary with `stove-container` + `containerApp()`. This validates the Dockerfile, entrypoint, base image, and runtime environment you plan to ship.

For fast iteration without an image, see [Process Mode](go-process.md). The same Kotlin tests can run against either mode when your `StoveConfig` switches only the AUT runner.

<div class="stove-tldr" markdown>
<span class="stove-tldr-title">In 30 seconds</span>
This page is the <strong>Go-specific recipe</strong>. The language-agnostic <code>stove-container</code> reference (full DSL, image-source patterns, networking, troubleshooting) lives at <a href="../../Components/22-container/">Container AUT</a>. Build the image however you build it (CI / registry / local Docker). Pass the tag to Stove. Reuse your process-mode StoveConfig with one runner branch.
</div>

## Why container mode

<div class="stove-compare" markdown="0">
  <div>
    <h4>Process mode</h4>
    <ul>
      <li>Fast. <code>go build</code> only</li>
      <li>Approximate prod parity (host runtime)</li>
      <li>Glibc/Alpine drift hidden</li>
      <li>Indirect CI validation</li>
      <li>Best for inner debug loop</li>
    </ul>
  </div>
  <div>
    <h4>Container mode</h4>
    <ul>
      <li>Slower. Image build (or fetch)</li>
      <li>Closer prod parity (the artifact you ship)</li>
      <li>Glibc/musl + locale + CA-cert issues surface</li>
      <li>Direct CI validation</li>
      <li>Best for pre-merge gating</li>
    </ul>
  </div>
</div>

Use container mode in CI to catch image-only regressions: missing CA certs, wrong base image, locale issues, glibc/musl drift. Keep process mode for the inner debug loop.

## What's different from process mode

Go application code, OpenTelemetry setup, Kafka bridge integration, and Stove test assertions can stay the same as [Process Mode](go-process.md). Container mode changes the AUT launch contract:

1. **AUT runner**. `containerApp(...)` instead of `goApp(...)` (see [container component page](../Components/22-container.md))
2. **Image source**. A tagged image. CI / registry / optional local build
3. **(Optional) Coverage volume**. Bind-mount a host dir into the container so coverage data survives container removal

Kotlin tests, registered Stove systems, and Go source can stay untouched if your configuration branch only swaps the AUT runner.

!!! info "Image build is not Stove's job"
    `containerApp(...)` only needs an image reference. Point it at your CI-produced tag, pull from a registry, or build locally. See [image source patterns](../Components/22-container.md#image-source-patterns) for the three options.

## (Optional) Reference Dockerfile

Your production Dockerfile works as-is. The one below is a minimal example if you're starting fresh.

```dockerfile title="Dockerfile.container"
FROM golang:1.26.2 AS build

WORKDIR /workspace
COPY go.mod go.sum ./
RUN go mod download
COPY *.go ./

ARG GO_BUILD_FLAGS=""
RUN CGO_ENABLED=0 GOOS=linux go build ${GO_BUILD_FLAGS} -o /out/go-showcase .

FROM alpine:3.23
WORKDIR /app
COPY --from=build /out/go-showcase /app/go-showcase

EXPOSE 8090
ENTRYPOINT ["/app/go-showcase"]
```

`GO_BUILD_FLAGS` build-arg threads `-cover` through the Docker build when coverage is on (process mode does this directly via `go build -cover`).

## Gradle setup

Minimum: a `Test` task that knows the image tag. Image can come from anywhere.

```kotlin title="build.gradle.kts"
// Resolve the image tag in priority order: env var → Gradle property → local fallback
val containerImage = providers.environmentVariable("APP_IMAGE")
    .orElse(providers.gradleProperty("app.image"))
    .orElse("stove-go-showcase-container:local")

tasks.register<Test>("e2eTest-container") {
    description = "Runs container-based e2e tests."
    group = "verification"
    useJUnitPlatform()
    systemProperty("go.aut.mode", "container")
    systemProperty("go.app.container.image", containerImage.get())
    systemProperty("kafka.library", "sarama")
}
```

In CI, point `APP_IMAGE` (or `-Papp.image=...`) at the tag your image-build job just produced. No `dependsOn("buildContainerImage")`. Stove just runs whatever is at that tag.

### (Optional) local-build convenience

Add a second test task that builds the image locally and runs against it. Keeps the CI-tag path untouched.

```kotlin title="build.gradle.kts"
val dockerExecutable = providers.environmentVariable("DOCKER_EXECUTABLE").getOrElse("docker")
val localImageTag = "my-service:local"

tasks.register<Exec>("buildContainerImage") {
    description = "Builds the application image locally."
    group = "build"
    commandLine(
        dockerExecutable, "build",
        "--file", projectDir.resolve("Dockerfile").absolutePath,
        "--tag", localImageTag,
        projectDir.absolutePath
    )
    inputs.file(project.file("Dockerfile"))
    inputs.files(fileTree(".") { include("*.go", "go.mod", "go.sum") })
    outputs.upToDateWhen { false }   // Docker is the source of truth
}

tasks.register<Test>("e2eTest-container-local") {
    description = "Builds the image locally and runs container e2e tests."
    group = "verification"
    dependsOn("buildContainerImage")
    useJUnitPlatform()
    systemProperty("go.app.container.image", localImageTag)
}
```

`buildContainerImage` isn't cached. Docker is the source of truth for image freshness. The CI task (`e2eTest-container`) does **not** depend on it. CI builds the image elsewhere and passes the tag.

## `StoveConfig.kt` (Go specifics)

A single `StoveConfig.kt` serves both modes by branching on a system property. Infrastructure systems (PostgreSQL, Kafka, tracing, dashboard) are identical to process mode. Only the AUT runner block changes:

```kotlin title="StoveConfig.kt"
containerApp(
    image = System.getProperty("go.app.container.image"),
    target = ContainerTarget.Server(
        hostPort = APP_PORT,
        internalPort = APP_PORT,
        portEnvVar = "APP_PORT",
        bindHostPort = false   // host network configured below; no host port binding
    ),
    envProvider = envMapper {
        // Stove → Go env var mapping (same keys as process mode)
        "database.host"        to "DB_HOST"
        "database.port"        to "DB_PORT"
        "database.name"        to "DB_NAME"
        "database.username"    to "DB_USER"
        "database.password"    to "DB_PASS"
        "kafka.bootstrapServers" to "KAFKA_BROKERS"
        env("OTEL_EXPORTER_OTLP_ENDPOINT", "localhost:$OTLP_PORT")
        env("KAFKA_LIBRARY", System.getProperty("kafka.library") ?: "sarama")
        env("STOVE_KAFKA_BRIDGE_PORT", stoveKafkaBridgePortDefault)
        env("GOCOVERDIR", coverageDirInContainer)
    },
    configureContainer = {
        withNetworkMode("host")
        if (hostCoverageDir.isNotBlank()) {
            withFileSystemBind(hostCoverageDir, COVERAGE_DIR_IN_CONTAINER)
        }
    }
)
```

Full `containerApp` reference (`ContainerTarget` variants, networking strategies, `configureContainer` capabilities): [container component page](../Components/22-container.md).

## Running

```bash
# CI / registry image: pass the tag in
./gradlew e2eTest-container -Papp.image=ghcr.io/your-org/my-service:sha-abc123
# or
APP_IMAGE=ghcr.io/your-org/my-service:sha-abc123 ./gradlew e2eTest-container

# Optional local-build path (when buildContainerImage is wired)
./gradlew e2eTest-container-local

# With Go coverage
./gradlew e2eTest-container -Pgo.coverage=true
```

<a id="code-coverage"></a>

## Code Coverage (Go-specific)

Container coverage works the same way as [process mode](go-process.md#code-coverage), with two extra wiring details unique to Go-in-a-container:

1. Dockerfile passes `${GO_BUILD_FLAGS}` so `-cover` reaches the build inside the image
2. Host coverage directory is bind-mounted into the container so data survives teardown

```kotlin
// In StoveConfig.kt
private const val COVERAGE_DIR_IN_CONTAINER = "/tmp/go-coverage"
val hostCoverageDir = System.getProperty("go.cover.dir").orEmpty()
val coverageDirInContainer = if (hostCoverageDir.isBlank()) "" else COVERAGE_DIR_IN_CONTAINER

containerApp(
    // ...
    envProvider = envMapper {
        // ...
        env("GOCOVERDIR", coverageDirInContainer)
    },
    configureContainer = {
        withNetworkMode("host")
        if (hostCoverageDir.isNotBlank()) {
            withFileSystemBind(hostCoverageDir, COVERAGE_DIR_IN_CONTAINER)
        }
    }
)
```

```bash
./gradlew e2eTest-containerWithCoverage -Pgo.coverage=true
# HTML report at build/go-coverage/coverage.html
```

`signal.Ignore(syscall.SIGPIPE)` in `main()` matters here too. Stove sends SIGTERM to stop the container; Go must finish flushing coverage data before the process dies.

## Dashboard & MCP

Container mode emits to the [Dashboard](../Components/18-dashboard.md) and [MCP server](../Components/21-mcp.md) when `dashboard { }` is registered and the `stove` CLI is running. The `appName` you set in `DashboardSystemOptions` is the label MCP uses to find runs:

```text
Agent calls stove_failures
  → finds failed runs for app_name=go-showcase
  → calls stove_failure_detail with run_id + test_id
  → drills into stove_trace to see Go spans
```

Tracing is `traceparent`-correlated when the Go app extracts incoming context and exports to Stove's OTLP endpoint, so a span captured inside the container can show up in the same trace tree as the originating Stove HTTP call.

## Pitfalls

| Symptom | Fix |
|---|---|
| Image not found | Tag mismatch; verify `APP_IMAGE` or `-Papp.image=...` |
| Container exits immediately | Entrypoint blocks? Check `docker logs <id>` |
| Coverage file empty | Bind-mount missing, or SIGPIPE killed Go before flush; ensure `signal.Ignore(syscall.SIGPIPE)` |
| Tests can't reach app | `withNetworkMode("host")` only works on Linux; on macOS/Windows use port binding (`bindHostPort = true`) |
| Env var ignored | Verify Go reads that exact variable name |

## Reference

- [Container AUT (`stove-container`)](../Components/22-container.md). DSL contract, networking, troubleshooting
- Container module source: `starters/container/stove-container/`
- Full working example: [`recipes/process/golang/go-showcase`](https://github.com/Trendyol/stove/tree/main/recipes/process/golang/go-showcase)
- Bridge library: [`go/stove-kafka`](https://github.com/Trendyol/stove/tree/main/go/stove-kafka)
- Component docs: [Dashboard](../Components/18-dashboard.md) · [MCP](../Components/21-mcp.md) · [Tracing](../Components/15-tracing.md)
