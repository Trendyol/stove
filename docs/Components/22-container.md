# Container AUT (`stove-container`)

Run the AUT as a **Docker image**. Any language, any framework. Image-level parity with what you ship to production.

<div class="stove-tldr" markdown>
<span class="stove-tldr-title">In 30 seconds</span>
Replace your framework starter with <code>containerApp(image = "my-app:tag", target = ContainerTarget.Server(...), envProvider = envMapper { ... })</code>. Stove starts the container, waits on a readiness probe, and exposes stable host ports. Image build is <em>your</em> responsibility, not Stove's.
</div>

For a host-binary AUT (process mode) see [`stove-process`](../other-languages/index.md). For a Go-specific walkthrough see [Go Container Mode](../other-languages/go-container.md).

<a id="image-source-patterns"></a>

## What `stove-container` does

- Pulls / locates the image; wraps it as a Testcontainers `GenericContainer`
- Maps Stove infrastructure config to env vars (`envMapper`) or CLI args (`argsMapper`)
- Waits for readiness via your chosen `ReadinessStrategy`
- Exposes stable host ports for tests
- Graceful shutdown with force-close fallback

What it does NOT do:

- :x: Build your image (use Docker, Bazel, ko, jib, whatever)
- :x: Manage your registry
- :x: Bridge into your app's DI container

## Configure

```kotlin
Stove().with {
  postgresql { /* ... */ }
  kafka      { /* ... */ }

  containerApp(
    image = "ghcr.io/your-org/your-app:local",
    target = ContainerTarget.Server(
      hostPort = 8090,
      internalPort = 8090,
      portEnvVar = "APP_PORT"
    ),
    envProvider = envMapper {
      // Stove config key -> AUT env var
      "postgresql.jdbc-url" to "DB_URL"
      "postgresql.username" to "DB_USER"
      "postgresql.password" to "DB_PASS"
      "kafka.bootstrap-servers" to "KAFKA_BROKERS"
    },
    readiness = ReadinessStrategy.HttpGet(path = "/health"),
    configureContainer = {
      withNetworkMode("host")   // Linux only; cross-platform = use port binding
      withFileSystemBind("./fixtures", "/app/fixtures")
    }
  )
}.run()
```

## Target

| Variant | Use |
|---|---|
| `ContainerTarget.Server(hostPort, internalPort, portEnvVar, bindHostPort = true)` | App listens on a port; readiness probe required |
| `ContainerTarget.Worker()` | No port; readiness via `Probe` or `FixedDelay` |

Use `hostPort = 0` for a dynamic, CI-safe port assignment.

## Readiness

```kotlin
ReadinessStrategy.HttpGet(path = "/health")
ReadinessStrategy.TcpPort                          // any TCP listener
ReadinessStrategy.Probe { container -> /* boolean */ }
ReadinessStrategy.FixedDelay(5.seconds)            // last resort
```

## Env vs CLI args

```kotlin
// env (most images)
envProvider = envMapper {
  "postgresql.jdbc-url" to "DB_URL"
}

// CLI args (some Go / Rust binaries)
argsProvider = argsMapper {
  "postgresql.jdbc-url" to "--db-url"
  "kafka.bootstrap-servers" to "--kafka"
}
```

Both can coexist on the same `containerApp`.

## Networking gotchas

| Mode | Pros | Cons |
|---|---|---|
| `withNetworkMode("host")` | App reaches `localhost:port` directly | Linux only |
| Port binding (default) | Cross-platform | Stove-managed infra reachable via Testcontainers network alias, not `localhost` |

For port binding, map infra hosts in `envProvider`:

```kotlin
envProvider = envMapper {
  "postgresql.host" to "DB_HOST"           // ends up = testcontainers alias
  "postgresql.port" to "DB_PORT"
  "kafka.bootstrap-servers" to "KAFKA"
}
```

## Bind mounts

```kotlin
configureContainer = {
  withFileSystemBind(
    File("./fixtures").absolutePath,
    "/app/fixtures",
    BindMode.READ_ONLY
  )
}
```

Useful for seed data, certificates, schema files.

## Coverage / shutdown hooks

`beforeStarted` hook fires before container start. `gracefulShutdownTimeout` controls SIGTERM patience before SIGKILL.

```kotlin
containerApp(
  image = "my-app:local",
  /* ... */,
  beforeStarted = { container -> /* tweak container */ },
  gracefulShutdownTimeout = 30.seconds
)
```

Required for languages that flush data on SIGTERM (e.g. Go integration coverage). See [Go Container Mode · Code Coverage](../other-languages/go-container.md#code-coverage).

## Pitfalls

| Symptom | Fix |
|---|---|
| Container exits immediately | Image entrypoint blocks? Check `docker logs <id>` |
| Readiness probe times out | App listens on `0.0.0.0`, not `127.0.0.1`; check port binding |
| Env var ignored | Confirm the AUT reads that exact variable name |
| Tests can't reach app | Mixing `host` network mode with port binding; pick one |

## Pairs well with

- [Polyglot overview](../other-languages/index.md) for non-JVM AUT patterns
- [Provided Application](19-provided-application.md) for remote-deployed apps
- [Multiple Systems](20-multiple-systems.md) for testing across multiple AUTs
