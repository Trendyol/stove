# <span data-rn="underline" data-rn-color="#ff9800">Go</span>

Stove treats Go as a <span data-rn="highlight" data-rn-color="#00968855" data-rn-duration="800">first-class application under test</span>. The same Stove DSL — `http {}`, `postgresql {}`, `kafka {}`, `tracing {}`, `dashboard {}` — drives a Go service end to end. Distributed traces, dashboard streams, and integration coverage all flow through the standard Stove pipeline.

The full source is at [`recipes/process/golang/go-showcase`](https://github.com/Trendyol/stove/tree/main/recipes/process/golang/go-showcase). One showcase, two AUT modes — pick the one that matches what you want to test.

## Pick a mode

| Mode | Starter | When to use | Trade-off |
|------|---------|-------------|-----------|
| **Process** | `stove-process` (`goApp` / `processApp`) | Fast local iteration, direct binary run, easiest debugging | You manage host runtime/binary alignment |
| **Container** | `stove-container` (`containerApp`) | CI parity with the production image, environment isolation | Image build adds setup cost |

Rule of thumb: start with [process mode](go-process.md) for fast feedback, then add [container mode](go-container.md) when you want image-level confidence in CI. The same Kotlin tests run against either.

<div class="grid cards" markdown>

-   :material-language-go: **Process Mode**

    Run the Go binary directly. Fastest iteration loop.

    [Process Mode guide :material-arrow-right:](go-process.md)

-   :material-docker: **Container Mode**

    Run the production Docker image. CI-grade parity.

    [Container Mode guide :material-arrow-right:](go-container.md)

</div>

## What you get out of the box

- **HTTP, PostgreSQL, Kafka, MongoDB, Redis, …** — every Stove system works against a Go AUT
- **Distributed tracing** via OpenTelemetry — spans from Go appear in the same trace tree as the test
- **Dashboard** — the Go run streams to `http://localhost:4040` like any JVM run
- **MCP triage** — failed Go runs are queryable through the [`stove` CLI MCP server](../Components/21-mcp.md)
- **Kafka assertions** — `shouldBePublished` / `shouldBeConsumed` work for Go via the [`stove-kafka`](https://github.com/trendyol/stove/tree/main/go/stove-kafka) bridge (sarama, franz-go, segmentio, or any client via the core API)
- **Integration coverage** — `go build -cover` + `GOCOVERDIR` collected on graceful shutdown, with HTML/summary reports

## Adapting for other languages

The same model works for any language. Replace the Go-specific parts (build step, OTel SDK, Kafka bridge):

| Part | Go | Python | Node.js | Rust |
|------|-----|--------|---------|------|
| **Build step** | `go build` | *(none or pip install)* | `npm install && npm run build` | `cargo build` |
| **AUT runner** | `goApp()` / `containerApp()` | `processApp()` / `containerApp()` | `processApp()` / `containerApp()` | `processApp()` / `containerApp()` |
| **OTel HTTP** | `otelhttp.NewHandler` | `opentelemetry-instrumentation-flask` | `@opentelemetry/instrumentation-http` | `tracing-opentelemetry` |
| **OTel DB** | `otelsql` | `opentelemetry-instrumentation-psycopg2` | `@opentelemetry/instrumentation-pg` | `tracing-opentelemetry` |
| **Kafka assertions** | `stove-kafka` bridge | *(bridge library needed)* | *(bridge library needed)* | *(bridge library needed)* |

The Kotlin test side stays exactly the same — only the AUT runner and config mapping differ.
