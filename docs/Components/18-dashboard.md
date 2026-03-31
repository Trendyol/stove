# <span data-rn="underline" data-rn-color="#ff9800">Dashboard</span>

Your end-to-end tests pass. But do you *see* what they do?

Stove Dashboard is a <span data-rn="highlight" data-rn-color="#00968855" data-rn-duration="800">local observability dashboard</span> for your e2e test runs.

- **Captures everything** — HTTP calls, Kafka messages, database queries, gRPC calls, distributed traces, system snapshots
- **Real-time web UI** — updates live via SSE as your tests execute
- **Single binary** — receives events via gRPC, persists in SQLite, serves an embedded SPA
- **Persistent** — browse test runs after they complete, across sessions

Unlike [Reporting](13-reporting.md) (console output on failure) and [Tracing](15-tracing.md) (span collection for assertions), Dashboard gives you a <span data-rn="underline" data-rn-color="#009688">persistent, browsable view</span> of your test runs — including successful ones.

## Install the CLI

=== "Homebrew (macOS)"

    ```bash
    brew install Trendyol/trendyol-tap/stove
    ```

=== "Shell Script (macOS & Linux)"

    ```bash
    curl -fsSL https://raw.githubusercontent.com/Trendyol/stove/main/tools/stove-cli/install.sh | sh
    ```

    Options:

    ```bash
    # Install a specific version
    curl -fsSL ... | sh -s -- --version 0.23.0

    # Install to a custom directory
    curl -fsSL ... | sh -s -- --dir /usr/local/bin
    ```

=== "Manual Download"

    Download the binary for your platform from [GitHub Releases](https://github.com/Trendyol/stove/releases):

    | Platform    | Archive                                      |
    |-------------|----------------------------------------------|
    | macOS arm64 | `stove-<version>-darwin-arm64.tar.gz` |
    | macOS amd64 | `stove-<version>-darwin-amd64.tar.gz` |
    | Linux amd64 | `stove-<version>-linux-amd64.tar.gz`  |

    Each archive includes a `.sha256` checksum file.

The CLI is a single binary with no runtime dependencies. It embeds the web UI, so there's nothing else to install.

## Quick Start

**1. Start the dashboard**

```bash
stove
```

You'll see:

```
Stove CLI v0.23.0
  HTTP server: http://localhost:4040
  gRPC server: http://localhost:4041
  Database: ~/.stove-dashboard.db
```

**2. Add the dependency**

=== "Gradle"

    ```kotlin hl_lines="3-4"
    dependencies {
        testImplementation(platform("com.trendyol:stove-bom:$version"))
        testImplementation("com.trendyol:stove-dashboard")
        testImplementation("com.trendyol:stove-tracing")
    }
    ```

=== "Maven"

    ```xml hl_lines="3-6"
    <dependency>
        <groupId>com.trendyol</groupId>
        <artifactId>stove-dashboard</artifactId>
        <scope>test</scope>
    </dependency>
    ```

**3. Register in your Stove config**

```kotlin hl_lines="3-4"
Stove()
  .with {
    dashboard { DashboardSystemOptions(appName = "product-api") }
    tracing { enableSpanReceiver() }  // recommended: enables distributed trace capture
    // ... other systems
  }.run()
```

**4. Run your tests and open the dashboard**

```bash
./gradlew test
```

Navigate to [http://localhost:4040](http://localhost:4040). The UI updates in real time as tests execute.

## What Gets Captured

Once `dashboard {}` is registered, Stove <span data-rn="highlight" data-rn-color="#4caf5044" data-rn-duration="800">automatically captures everything</span> — no code changes to your tests:

| Event              | Data                                                         |
|--------------------|--------------------------------------------------------------|
| **Run lifecycle**  | Start/end timestamps, app name, active systems, pass/fail counts |
| **Test lifecycle** | Test name, spec name, duration, status, error messages       |
| **Entries**        | Every `http {}`, `kafka {}`, `postgresql {}` assertion — system, action, input/output, expected/actual, trace ID |
| **Spans**          | Distributed traces via OpenTelemetry — operation, service, duration, attributes, exceptions |
| **Snapshots**      | System state at test boundaries — database contents, Kafka offsets, WireMock stubs |

## The Dashboard

The embedded SPA provides four views for each test:

### Timeline

Chronological list of every action the test performed. Each entry shows timestamp, system badge (color-coded), action name, and pass/fail indicator. Click any entry to expand full detail: input, output, expected vs. actual, error, metadata.

Recognized systems: <span data-rn="highlight" data-rn-color="#00968855">HTTP, Kafka, PostgreSQL, MongoDB, Couchbase, Redis, Elasticsearch, WireMock, gRPC, MySQL, MSSQL, Cassandra</span>.

### Trace

Distributed trace tree built from OpenTelemetry spans. Spans are linked to tests via two mechanisms:

- **Entry-based**: spans sharing a `trace_id` with a test entry
- **Attribute-based**: spans containing `x-stove-test-id` in their attributes

The tree shows operation name, service, duration, status, relevant attributes (`http.*`, `db.*`, `messaging.*`, `rpc.*`), and exception details with stack traces.

!!! tip "Combine with Tracing"
    Dashboard's trace view is the visual counterpart to the [Tracing](15-tracing.md) component's console output. Enable both for the best experience: Tracing gives you assertion DSL and failure reports in the terminal, Dashboard gives you a browsable trace tree in the browser.

### Snapshots

Grid of system state cards captured at test boundaries. Each card shows the system name with a color-coded icon and a summary of the captured state.

### Kafka Explorer

Dedicated view filtering Kafka-specific entries. Shows consumed/published/failed message counts with expandable JSON payloads.

## Configuration

### DashboardSystemOptions

```kotlin
DashboardSystemOptions(
  appName = "product-api",     // required: identifies the application under test
  cliHost = "localhost",       // where the stove CLI is running
  cliPort = 4041               // gRPC port of the stove CLI
)
```

| Parameter | Type     | Default       | Description                                |
|-----------|----------|---------------|--------------------------------------------|
| `appName` | `String` | *(required)*  | Application name for grouping test runs    |
| `cliHost` | `String` | `"localhost"` | Hostname where `stove` CLI is running      |
| `cliPort` | `Int`    | `4041`        | gRPC port where `stove` CLI is listening   |

### CLI Options

```
stove [OPTIONS]

Options:
  --port <PORT>          HTTP port for the web UI and REST API [default: 4040]
  --grpc-port <PORT>     gRPC port for receiving events [default: 4041]
  --db <PATH>            Path to SQLite database file [default: ~/.stove-dashboard.db]
  --clear                Clear all stored data and exit
  --fresh-start          Back up and recreate the database, then start normally
  -h, --help             Print help
  -V, --version          Print version
```

```bash
# Run on custom ports
stove --port 8080 --grpc-port 8081

# Use a project-specific database
stove --db ./my-project-dashboard.db

# Reset all data (exits after clearing)
stove --clear

# Drop and recreate the database (backs up first, then starts servers)
stove --fresh-start
```

## Fault Tolerance

The dashboard emitter is designed to <span data-rn="underline" data-rn-color="#009688">never break your tests</span>:

- Non-blocking event queue (capacity: 512)
- Auto-disables after 5 consecutive gRPC failures
- 3-second drain timeout on shutdown
- If the dashboard CLI is not running, tests continue normally with zero overhead

This means you can add `dashboard {}` to your config permanently. When the CLI is running, you get the dashboard. When it's not, nothing changes.

## REST API

The dashboard exposes a REST API at `/api/v1` for programmatic access:

| Method | Path                                         | Description                    |
|--------|----------------------------------------------|--------------------------------|
| GET    | `/apps`                                      | List applications with latest run info |
| GET    | `/runs?app={name}`                           | List runs, optionally filtered by app  |
| GET    | `/runs/{run_id}`                             | Get a specific run             |
| GET    | `/runs/{run_id}/tests`                       | List tests in a run            |
| GET    | `/runs/{run_id}/tests/{test_id}/entries`     | List entries for a test        |
| GET    | `/runs/{run_id}/tests/{test_id}/spans`       | List spans linked to a test    |
| GET    | `/runs/{run_id}/tests/{test_id}/snapshots`   | List snapshots for a test      |
| GET    | `/traces/{trace_id}`                         | Get all spans in a trace       |
| GET    | `/events/stream`                             | SSE stream for real-time events |

### SSE Events

The `/events/stream` endpoint delivers server-sent events with JSON payloads:

```json
{"run_id": "abc-123", "event_type": "test_ended"}
```

Event types: `run_started`, `run_ended`, `test_started`, `test_ended`, `entry_recorded`, `span_recorded`, `snapshot`.

## Complete Example

```kotlin hl_lines="7-8"
class StoveConfig : AbstractProjectConfig() {
  override val extensions = listOf(StoveKotestExtension())

  override suspend fun beforeProject() =
    Stove()
      .with {
        dashboard { DashboardSystemOptions(appName = "spring-example") }
        tracing { enableSpanReceiver() }
        httpClient {
          HttpClientSystemOptions(baseUrl = "http://localhost:$appPort")
        }
        postgresql {
          PostgresqlOptions(databaseName = "stove", configureExposedConfiguration = { cfg ->
            listOf("spring.datasource.url=${cfg.jdbcUrl}")
          })
        }
        kafka {
          KafkaSystemOptions(configureExposedConfiguration = {
            listOf("kafka.bootstrapServers=${it.bootstrapServers}")
          })
        }
        springBoot(runner = { params -> run(params) { addTestSystemDependencies() } })
      }.run()

  override suspend fun afterProject() = Stove.stop()
}
```

Then write tests as usual — the dashboard captures everything automatically:

```kotlin
test("should create order and publish event") {
  stove {
    http {
      postAndExpectBodilessResponse("/orders", body = CreateOrderRequest(orderId).some()) {
        it.status shouldBe 201
      }
    }

    kafka {
      shouldBePublished<OrderCreatedEvent> {
        actual.orderId == orderId
      }
    }

    postgresql {
      shouldQuery<Order>("SELECT * FROM orders WHERE id = '$orderId'") {
        it.first().status shouldBe "CREATED"
      }
    }
  }
}
```

Open [http://localhost:4040](http://localhost:4040) to see every HTTP request, Kafka message, database query, and distributed trace — in real time.

## How It Relates to Reporting and Tracing

Dashboard, [Reporting](13-reporting.md), and [Tracing](15-tracing.md) are complementary:

| Feature | Reporting | Tracing | Dashboard |
|---------|-----------|---------|--------|
| When | On test failure | On test failure | Always (real-time) |
| Where | Console/CI output | Console/CI output | Browser UI |
| What | Test actions + assertions | Application call chain | Everything + history |
| Persistence | None (ephemeral) | None (ephemeral) | SQLite (across runs) |

<span data-rn="highlight" data-rn-color="#4caf5044" data-rn-duration="800">Use all three together</span> for the best experience:

- **Reporting** gives you immediate feedback in the terminal when something breaks
- **Tracing** gives you the execution trace and assertion DSL in your test code
- **Dashboard** gives you a browsable, persistent view of all test runs — successful and failed

## Troubleshooting

### Dashboard UI shows "Waiting for test events..."

1. Verify the `stove` CLI is running: `stove --version`
2. Check that gRPC ports match: CLI default is `4041`, Kotlin default is `4041`
3. Look for connection errors in the CLI's terminal output

### Tests run fine but nothing appears in Dashboard

1. Ensure `dashboard {}` is registered in your Stove config
2. Verify `stove-dashboard` is in your test dependencies
3. Check that the CLI started *before* running tests

### Dashboard works locally but not in CI

Dashboard is designed for <span data-rn="underline" data-rn-color="#009688">local development</span>. In CI, use [Reporting](13-reporting.md) and [Tracing](15-tracing.md) for failure diagnostics — they output to the console and don't require a running server.

### Data from previous runs clutters the UI

```bash
stove --clear
```

This wipes the SQLite database and exits. Start the CLI again for a clean slate.

### Database schema is corrupted or migrations fail

```bash
stove --fresh-start
```

This backs up the existing database (printing the backup path), deletes it, and recreates a fresh one with all migrations applied. The servers start normally after — no need to run `stove` again.
