# Go — Container Mode

Run the Go application as a Docker image instead of a host binary using `stove-container` and the `containerApp()` DSL. This gives you <span data-rn="highlight" data-rn-color="#00968855" data-rn-duration="800">image-level parity with what you ship to production</span> — same Dockerfile, same entrypoint, same runtime — without changing a single line of Stove test code.

For fast iteration without an image, see [Process Mode](go-process.md). The same Kotlin tests run against either.

This page is the **Go-specific recipe**. For the language-agnostic `stove-container` reference — full DSL contract, image-source patterns, networking strategies, `configureContainer`, `beforeStarted`, troubleshooting matrix — see [Container AUT (`stove-container`)](../Components/22-container.md). The Go showcase below uses that module; it does not redefine it.

## Why container mode (Go-specific summary)

| Concern | Process mode | Container mode |
|---------|--------------|----------------|
| **Iteration speed** | Fast — `go build` only | Slower — image build (or fetch from registry) |
| **Production parity** | Approximate (host runtime) | Exact (the artifact you ship) |
| **Glibc / Alpine differences** | Hidden | Surfaced |
| **CI/CD validation** | Indirect | Direct |

Use container mode in CI to catch image-only regressions (missing CA certs, wrong base image, locale issues, glibc/musl drift). Keep process mode for the inner debug loop.

## What this guide adds on top of Process Mode

The Go application code, OpenTelemetry setup, Kafka bridge integration, and Stove test DSL are identical to [Process Mode](go-process.md). Container mode only changes:

1. **AUT runner** — `containerApp(...)` instead of `goApp(...)` (see the [container component page](../Components/22-container.md))
2. **Image source** — a tagged image, from CI / a registry / or an optional local build
3. **(Optional) Coverage volume** — bind-mount a host directory into the container so coverage data survives container removal

The Kotlin tests, the Stove DSL, the Stove systems, and the Go source code do not change.

!!! info "Image build is not Stove's job"
    `containerApp(...)` only needs an image reference. Use whatever your CI already produced, pull from a registry, or wire an optional local Gradle build task — see [image source patterns](../Components/22-container.md#image-source-patterns) for the three options. The Dockerfile and `buildContainerImage` task below are the *recipe's* convenience for being self-contained, not a requirement.

## (Optional) Dockerfile for the showcase

The recipe includes a Dockerfile so the repo is self-contained. In a real Go project, this is whatever your team already ships to production.

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

The `GO_BUILD_FLAGS` build-arg is what threads `-cover` through the Docker build when coverage is enabled (process mode does this with `go build -cover` directly).

## Gradle Setup

The minimum: a `Test` task that knows the image tag. The image can come from anywhere.

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

In CI, point `APP_IMAGE` (or `-Papp.image=...`) at the tag your image-build job just produced. No `dependsOn("buildContainerImage")` needed — Stove just runs whatever is at that tag.

### (Optional) Local build convenience

If you also want a one-command local-build path, wire the Docker build as a separate task and add a *separate* test task that depends on it. Keep the CI-tag path untouched.

```kotlin title="build.gradle.kts"
val dockerExecutable = providers.environmentVariable("DOCKER_EXECUTABLE").getOrElse("docker")
val coverageEnabled = providers.gradleProperty("go.coverage")
    .map { it.toBoolean() }.getOrElse(false)
val localImageTag = "stove-go-showcase-container:local"

tasks.register<Exec>("buildContainerImage") {
    description = "Optional convenience: builds the Go showcase Docker image locally."
    group = "build"
    dependsOn("goModTidy")
    val buildFlags = if (coverageEnabled) "-cover" else ""
    commandLine(
        dockerExecutable, "build",
        "--file", projectDir.resolve("Dockerfile.container").absolutePath,
        "--tag", localImageTag,
        "--build-arg", "GO_BUILD_FLAGS=$buildFlags",
        projectDir.absolutePath
    )
    inputs.file(project.file("Dockerfile.container"))
    inputs.files(fileTree(".") { include("*.go", "go.mod", "go.sum") })
    outputs.upToDateWhen { false }   // Docker is the source of truth
}

tasks.register<Exec>("removeContainerImage") {
    description = "Removes the locally-built image."
    group = "build"
    commandLine(dockerExecutable, "image", "rm", localImageTag)
    isIgnoreExitValue = true
}

// Local-build path — only this task triggers a build
tasks.register<Test>("e2eTest-container-local") {
    description = "Builds the image locally and runs container e2e tests."
    group = "verification"
    dependsOn("buildContainerImage")
    useJUnitPlatform()
    systemProperty("go.aut.mode", "container")
    systemProperty("go.app.container.image", localImageTag)
    systemProperty("kafka.library", "sarama")
    if (coverageEnabled) {
        systemProperty("go.cover.dir", goCoverDirPath)
        outputs.cacheIf { false }
    }
}
```

`buildContainerImage` is intentionally not cached — Docker is the source of truth for image freshness. The CI test task (`e2eTest-container`) does **not** depend on it.

## Stove Configuration (Go specifics)

A single `StoveConfig.kt` can serve both modes by branching on a system property. The infrastructure systems (PostgreSQL, Kafka, tracing, dashboard) are identical to process mode — only the AUT runner block changes:

```kotlin title="StoveConfig.kt"
containerApp(
    image = System.getProperty("go.app.container.image"),
    target = ContainerTarget.Server(
        hostPort = APP_PORT,
        internalPort = APP_PORT,
        portEnvVar = "APP_PORT",
        bindHostPort = false   // host network — no need to bind
    ),
    envProvider = envMapper {
        // Stove → Go env var mapping (same keys as process mode)
        "database.host" to "DB_HOST"
        "database.port" to "DB_PORT"
        "database.name" to "DB_NAME"
        "database.username" to "DB_USER"
        "database.password" to "DB_PASS"
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

For the full list of `containerApp` parameters, `ContainerTarget` variants, networking strategies (`host` vs port-binding), and `configureContainer` capabilities, see the [container component page](../Components/22-container.md).

## Running

```bash
# CI / registry image — pass the tag in
./gradlew e2eTest-container -Papp.image=ghcr.io/acme/go-showcase:sha-abc123
# or
APP_IMAGE=ghcr.io/acme/go-showcase:sha-abc123 ./gradlew e2eTest-container

# Optional local-build path (only when you wired buildContainerImage)
./gradlew e2eTest-container-local

# Container e2e with Go coverage
./gradlew e2eTest-containerWithCoverage -Pgo.coverage=true

# Remove the locally-built image when done
./gradlew removeContainerImage

# Use locally-published Stove artifacts (e.g. before a snapshot release)
./gradlew e2eTest-container -PuseMavenLocal=true
```

By default the recipe resolves Stove from Maven Central + Sonatype snapshots so CI validates the same published path that users consume. `mavenLocal()` is opt-in.

## Code Coverage (Go-specific)

Container coverage works the same way as [process mode](go-process.md#code-coverage), with two extra wiring details unique to Go-in-a-container:

1. The `Dockerfile` passes `${GO_BUILD_FLAGS}` so `-cover` reaches the build inside the image
2. The host coverage directory is bind-mounted into the container so data survives container teardown

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

`signal.Ignore(syscall.SIGPIPE)` in `main()` matters here too — Stove sends SIGTERM to stop the container, and Go must finish flushing coverage data before the process dies.

## Dashboard & MCP

Container mode emits to the [Stove Dashboard](../Components/18-dashboard.md) and the [MCP server](../Components/21-mcp.md) the same way process mode does. The `appName` you set in `DashboardSystemOptions` is the only label MCP needs to find the right runs:

```text
Agent calls stove_failures
  → finds failed runs for app_name=go-showcase
  → calls stove_failure_detail with run_id + test_id
  → drills into stove_trace to see Go spans
```

Because tracing is `traceparent`-correlated, a Go span captured inside the container shows up in the same trace tree as the originating Stove HTTP call — no additional plumbing required.

## Reference

- Container component page (DSL contract, networking, troubleshooting): [Container AUT (`stove-container`)](../Components/22-container.md)
- Container module source: `starters/container/stove-container/`
- Full working example (process **and** container modes in one repo): [`recipes/process/golang/go-showcase`](https://github.com/Trendyol/stove/tree/main/recipes/process/golang/go-showcase)
- Bridge library source: [`go/stove-kafka`](https://github.com/Trendyol/stove/tree/main/go/stove-kafka)
- Component docs: [Dashboard](../Components/18-dashboard.md) · [MCP](../Components/21-mcp.md) · [Tracing](../Components/15-tracing.md)
