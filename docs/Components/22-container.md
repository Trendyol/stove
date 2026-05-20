# Container AUT (`stove-container`)

Run the AUT as a **Docker image**. Any language, any framework, with the same entrypoint and runtime image you ship.

<div class="stove-tldr" markdown>
<span class="stove-tldr-title">In 30 seconds</span>
Replace your framework starter with <code>containerApp(image = "my-app:tag", target = ContainerTarget.Server(...), envProvider = envMapper { ... })</code>. Stove starts the container, maps system configuration into env vars or CLI args, and waits on your readiness probe. Image build is <em>your</em> responsibility, not Stove's.
</div>

For a host-binary AUT (process mode) see [`stove-process`](../other-languages/index.md). For a Go-specific walkthrough see [Go Container Mode](../other-languages/go-container.md).

<a id="image-source-patterns"></a>

## What `stove-container` does

- Pulls or locates the image and wraps it as a Testcontainers `GenericContainer`
- Maps Stove infrastructure config to env vars (`envMapper`) or CLI args (`argsMapper`)
- Waits for readiness via your chosen `ReadinessStrategy`
- Exposes host ports according to `ContainerTarget.Server` and your port-binding strategy
- Graceful shutdown with force-close fallback

What it does NOT do:

- :x: Build your image (use Docker, Bazel, ko, jib, whatever)
- :x: Manage your registry
- :x: Bridge into your app's DI container

## Configure

```kotlin
import com.trendyol.stove.container.ContainerTarget
import com.trendyol.stove.container.containerApp
import com.trendyol.stove.system.ReadinessStrategy
import com.trendyol.stove.system.application.envMapper

Stove().with {
  postgresql { /* ... */ }
  kafka      { /* ... */ }

  containerApp(
    image = "ghcr.io/your-org/your-app:local",
    target = ContainerTarget.Server(
      hostPort = 8090,
      internalPort = 8090,
      portEnvVar = "APP_PORT",
      bindHostPort = true,
      readiness = ReadinessStrategy.HttpGet(url = "http://localhost:8090/health")
    ),
    envProvider = envMapper {
      // Left side must match the keys your systems expose.
      "database.host" to "DB_HOST"
      "database.port" to "DB_PORT"
      "database.name" to "DB_NAME"
      "kafka.bootstrapServers" to "KAFKA_BROKERS"
    },
    configureContainer = {
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

`ContainerTarget.Worker(readiness = ...)` accepts no port because workers do not expose a listening socket. Use a stable `hostPort` when tests call the AUT through `localhost`. Use `hostPort = 0` only when tests can discover the mapped port and readiness does not depend on a fixed localhost URL.

## Readiness

```kotlin
ReadinessStrategy.HttpGet(url = "http://localhost:8090/health")
ReadinessStrategy.TcpPort(port = 8090)             // any TCP listener
ReadinessStrategy.Probe { /* boolean */ }
ReadinessStrategy.FixedDelay(5.seconds)            // last resort
```

## Env vs CLI args

```kotlin
// env (most images)
envProvider = envMapper {
  "database.host" to "DB_HOST"
}

// CLI args (some Go / Rust binaries)
argsProvider = argsMapper {
  "database.host" to "db-host"
  "kafka.bootstrapServers" to "kafka"
}
```

Both can coexist on the same `containerApp`; use the shape your image already supports.

## Networking gotchas

| Mode | Pros | Cons |
|---|---|---|
| `withNetworkMode("host")` | App reaches `localhost:port` directly | Linux only |
| Port binding (default) | Cross-platform | Stove-managed infra reachable via Testcontainers network alias, not `localhost` |

For port binding, map infrastructure hosts in `envProvider` to values the container can reach:

```kotlin
envProvider = envMapper {
  "postgresql.host" to "DB_HOST"           // ends up = testcontainers alias
  "postgresql.port" to "DB_PORT"
  "kafka.bootstrapServers" to "KAFKA"
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
  beforeStarted = { configurations -> /* write config files, seed dirs, etc. */ },
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
