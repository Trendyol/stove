# Go

Stove can run a Go service as the application under test through process or container runners. The Kotlin test DSL keeps the same shape for registered systems; Go-specific work is build wiring, env/arg configuration, OTel SDK setup, and optional Kafka bridge integration.

<div class="stove-tldr" markdown>
<span class="stove-tldr-title">Two modes, one DSL</span>
Process mode runs the Go binary for fast iteration. Container mode runs a Docker image for artifact-level parity. The same Kotlin tests can target either mode when `StoveConfig` branches on one option.
</div>

Full reference source: [`recipes/process/golang/go-showcase`](https://github.com/Trendyol/stove/tree/main/recipes/process/golang/go-showcase).

## Pick a mode

<div class="stove-compare" markdown="0">
  <div>
    <h4>🏃 Process mode</h4>
    <p><code>stove-process</code> · <code>goApp()</code> · <code>processApp()</code></p>
    <ul>
      <li>Fast local iteration</li>
      <li>Direct binary run, easiest debugging</li>
      <li>You manage host runtime alignment</li>
    </ul>
    <p><a href="../go-process/" class="stove-btn primary">Process mode guide →</a></p>
  </div>
  <div>
    <h4>🐳 Container mode</h4>
    <p><code>stove-container</code> · <code>containerApp()</code></p>
    <ul>
      <li>CI parity with production image</li>
      <li>Environment isolation</li>
      <li>Image build adds setup cost</li>
    </ul>
    <p><a href="../go-container/" class="stove-btn primary">Container mode guide →</a></p>
  </div>
</div>

Rule of thumb: start with **process mode** for fast feedback, add **container mode** in CI when you want image-level confidence. The runner changes; the external-surface assertions can stay the same.

## What you get

<div class="stove-ribbon" markdown="0">
  <div class="stove-ribbon-item">
    <div class="icon">🧱</div>
    <strong>External systems stay testable</strong>
    <p>HTTP, PostgreSQL, Kafka, MongoDB, Redis, and other registered systems use the same Kotlin-side DSL. The Go app must receive their connection details through env vars or CLI args.</p>
  </div>
  <div class="stove-ribbon-item">
    <div class="icon">🛰️</div>
    <strong>Distributed tracing</strong>
    <p>Spans from Go appear in the same trace tree as the test. <code>stoveTracing</code> Gradle plugin starts the OTLP receiver.</p>
  </div>
  <div class="stove-ribbon-item">
    <div class="icon">📊</div>
    <strong>Dashboard streams</strong>
    <p>Go runs stream to the dashboard like any JVM run. Timelines, snapshots, Kafka explorer.</p>
  </div>
  <div class="stove-ribbon-item">
    <div class="icon">🤖</div>
    <strong>MCP triage</strong>
    <p>Failed Go runs queryable through <code>stove</code> CLI MCP server.</p>
  </div>
  <div class="stove-ribbon-item">
    <div class="icon">📨</div>
    <strong>Kafka assertions</strong>
    <p><code>shouldBePublished</code> / <code>shouldBeConsumed</code> via the <a href="https://github.com/trendyol/stove/tree/main/go/stove-kafka">stove-kafka</a> bridge. Sarama, franz-go, and segmentio/kafka-go have adapters; other clients can call the core bridge directly.</p>
  </div>
  <div class="stove-ribbon-item">
    <div class="icon">📈</div>
    <strong>Integration coverage</strong>
    <p><code>go build -cover</code> + <code>GOCOVERDIR</code> collected on graceful shutdown. HTML + summary reports.</p>
  </div>
</div>

## Adapting for other languages

Same shape, swap the Go-specific parts.

| Part | Go | Python | Node.js | Rust |
|---|---|---|---|---|
| Build | `go build` | *(none / pip install)* | `npm install && build` | `cargo build` |
| AUT runner | `goApp()` / `containerApp()` | `processApp()` / `containerApp()` | `processApp()` / `containerApp()` | `processApp()` / `containerApp()` |
| OTel HTTP | `otelhttp.NewHandler` | `opentelemetry-instrumentation-flask` | `@opentelemetry/instrumentation-http` | `tracing-opentelemetry` |
| OTel DB | `otelsql` | `opentelemetry-instrumentation-psycopg2` | `@opentelemetry/instrumentation-pg` | `tracing-opentelemetry` |
| Kafka assertions | `stove-kafka` bridge | *(bridge needed)* | *(bridge needed)* | *(bridge needed)* |

The Kotlin test side keeps the same shape. The AUT runner, config mapping, and language-specific instrumentation are the parts that differ.
