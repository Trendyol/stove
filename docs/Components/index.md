# Systems

Stove is pluggable. Each dependency, test-side client, mock, and observability surface is a separate system module. AUT
runners are registered separately to start or target the application under test. Add only what your tests need. Default mode uses [Testcontainers](https://testcontainers.com/); switch to
[Provided Instances](11-provided-instances.md) when the infrastructure already exists or Docker is unavailable.

<div class="stove-tldr" markdown>
<span class="stove-tldr-title">Most teams start small</span>
Two to four systems usually gets a useful first suite: one driver such as HTTP or gRPC, and the stateful systems the use
case touches. Then register one AUT runner. Add tracing and dashboard when failure diagnosis needs more than console
output.
</div>

## Pick a starting set

| You're testing | Add these |
|---|---|
| HTTP API + SQL | `stove-http` + `stove-postgres` (or `-mysql`) |
| Event-driven service | `stove-kafka` + your DB + `stove-tracing` |
| Service calling external API | `stove-http` + `stove-wiremock` |
| gRPC service | `stove-grpc` + `stove-grpc-mock` |
| Stateful service with caching | your DB + `stove-redis` |
| Already-running service (any language) | `stove-http` + any provided dependencies + `providedApplication()` |

Wizard composes this for you: <a class="open-in-wizard" data-sys="http,postgresql,kafka" data-mk="wiremock">open with Postgres + Kafka + WireMock</a>.

## Catalog

<div class="stove-cat-group" markdown="0">
<h3>🗄 Databases</h3>
<div class="stove-catalog">

  <div class="stove-sys-card">
    <div class="stove-sys-card-head"><strong>PostgreSQL</strong><span class="stove-sys-card-badge">SQL</span></div>
    <p class="stove-sys-card-desc">Relational. Migrations, transactions, full SQL DSL.</p>
    <div class="stove-sys-card-actions">
      <a href="06-postgresql/">Reference</a>
      <a class="open-in-wizard" data-sys="postgresql">→ wizard</a>
    </div>
  </div>
  <div class="stove-sys-card">
    <div class="stove-sys-card-head"><strong>MySQL</strong><span class="stove-sys-card-badge">SQL</span></div>
    <p class="stove-sys-card-desc">Relational alternative for MySQL-targeted apps.</p>
    <div class="stove-sys-card-actions">
      <a href="16-mysql/">Reference</a>
      <a class="open-in-wizard" data-sys="mysql">→ wizard</a>
    </div>
  </div>
  <div class="stove-sys-card">
    <div class="stove-sys-card-head"><strong>MSSQL</strong><span class="stove-sys-card-badge">SQL</span></div>
    <p class="stove-sys-card-desc">Microsoft SQL Server with T-SQL support.</p>
    <div class="stove-sys-card-actions">
      <a href="08-mssql/">Reference</a>
      <a class="open-in-wizard" data-sys="mssql">→ wizard</a>
    </div>
  </div>
  <div class="stove-sys-card">
    <div class="stove-sys-card-head"><strong>MongoDB</strong><span class="stove-sys-card-badge">Document</span></div>
    <p class="stove-sys-card-desc">JSON document storage with aggregation.</p>
    <div class="stove-sys-card-actions">
      <a href="07-mongodb/">Reference</a>
      <a class="open-in-wizard" data-sys="mongodb">→ wizard</a>
    </div>
  </div>
  <div class="stove-sys-card">
    <div class="stove-sys-card-head"><strong>Couchbase</strong><span class="stove-sys-card-badge">Document</span></div>
    <p class="stove-sys-card-desc">N1QL queries, KV ops, FTS.</p>
    <div class="stove-sys-card-actions">
      <a href="01-couchbase/">Reference</a>
      <a class="open-in-wizard" data-sys="couchbase">→ wizard</a>
    </div>
  </div>
  <div class="stove-sys-card">
    <div class="stove-sys-card-head"><strong>Cassandra</strong><span class="stove-sys-card-badge">Wide-column</span></div>
    <p class="stove-sys-card-desc">Time-series, IoT, large-scale writes. CQL DSL.</p>
    <div class="stove-sys-card-actions">
      <a href="17-cassandra/">Reference</a>
      <a class="open-in-wizard" data-sys="cassandra">→ wizard</a>
    </div>
  </div>
  <div class="stove-sys-card">
    <div class="stove-sys-card-head"><strong>Redis</strong><span class="stove-sys-card-badge">KV</span></div>
    <p class="stove-sys-card-desc">In-memory cache, sessions, pub/sub.</p>
    <div class="stove-sys-card-actions">
      <a href="09-redis/">Reference</a>
      <a class="open-in-wizard" data-sys="redis">→ wizard</a>
    </div>
  </div>
  <div class="stove-sys-card">
    <div class="stove-sys-card-head"><strong>Elasticsearch</strong><span class="stove-sys-card-badge">Search</span></div>
    <p class="stove-sys-card-desc">Full-text search and analytics.</p>
    <div class="stove-sys-card-actions">
      <a href="03-elasticsearch/">Reference</a>
      <a class="open-in-wizard" data-sys="elasticsearch">→ wizard</a>
    </div>
  </div>

</div>
</div>

<div class="stove-cat-group" markdown="0">
<h3>📨 Messaging</h3>
<div class="stove-catalog">
  <div class="stove-sys-card">
    <div class="stove-sys-card-head"><strong>Kafka</strong><span class="stove-sys-card-badge">Pub/Sub</span></div>
    <p class="stove-sys-card-desc">Publish/consume assertions, interceptor-based capture, time-bounded waits.</p>
    <div class="stove-sys-card-actions">
      <a href="02-kafka/">Reference</a>
      <a class="open-in-wizard" data-sys="kafka">→ wizard</a>
    </div>
  </div>
</div>
</div>

<div class="stove-cat-group" markdown="0">
<h3>🌐 Network</h3>
<div class="stove-catalog">
  <div class="stove-sys-card">
    <div class="stove-sys-card-head"><strong>HTTP Client</strong><span class="stove-sys-card-badge">Drive</span></div>
    <p class="stove-sys-card-desc">Drive your app's HTTP API and assert decoded responses.</p>
    <div class="stove-sys-card-actions">
      <a href="05-http/">Reference</a>
      <a class="open-in-wizard" data-sys="http">→ wizard</a>
    </div>
  </div>
  <div class="stove-sys-card">
    <div class="stove-sys-card-head"><strong>gRPC Client</strong><span class="stove-sys-card-badge">Drive</span></div>
    <p class="stove-sys-card-desc">Call your gRPC services with Wire and grpc-kotlin clients.</p>
    <div class="stove-sys-card-actions">
      <a href="12-grpc/">Reference</a>
      <a class="open-in-wizard" data-sys="grpc">→ wizard</a>
    </div>
  </div>
</div>
</div>

<div class="stove-cat-group" markdown="0">
<h3>🪞 Mocks</h3>
<div class="stove-catalog">
  <div class="stove-sys-card">
    <div class="stove-sys-card-head"><strong>WireMock</strong><span class="stove-sys-card-badge">HTTP</span></div>
    <p class="stove-sys-card-desc">Mock third-party HTTP services at the network edge.</p>
    <div class="stove-sys-card-actions">
      <a href="04-wiremock/">Reference</a>
      <a class="open-in-wizard" data-mk="wiremock">→ wizard</a>
    </div>
  </div>
  <div class="stove-sys-card">
    <div class="stove-sys-card-head"><strong>gRPC Mock</strong><span class="stove-sys-card-badge">gRPC</span></div>
    <p class="stove-sys-card-desc">Mock external gRPC services.</p>
    <div class="stove-sys-card-actions">
      <a href="14-grpc-mock/">Reference</a>
      <a class="open-in-wizard" data-mk="grpc-mock">→ wizard</a>
    </div>
  </div>
</div>
</div>

<div class="stove-cat-group" markdown="0">
<h3>📈 Observability</h3>
<div class="stove-catalog">
  <div class="stove-sys-card">
    <div class="stove-sys-card-head"><strong>Reporting</strong><span class="stove-sys-card-badge">Console</span></div>
    <p class="stove-sys-card-desc">Console and JSON failure reports with timelines and system snapshots.</p>
    <div class="stove-sys-card-actions"><a href="13-reporting/">Reference</a></div>
  </div>
  <div class="stove-sys-card">
    <div class="stove-sys-card-head"><strong>Tracing</strong><span class="stove-sys-card-badge">OTel</span></div>
    <p class="stove-sys-card-desc">OpenTelemetry spans and validation DSL for application call chains.</p>
    <div class="stove-sys-card-actions"><a href="15-tracing/">Reference</a></div>
  </div>
  <div class="stove-sys-card">
    <div class="stove-sys-card-head"><strong>Dashboard</strong><span class="stove-sys-card-badge">Local UI</span></div>
    <p class="stove-sys-card-desc">SQLite-backed local UI for timelines, traces, and snapshots.</p>
    <div class="stove-sys-card-actions"><a href="18-dashboard/">Reference</a></div>
  </div>
  <div class="stove-sys-card">
    <div class="stove-sys-card-head"><strong>MCP</strong><span class="stove-sys-card-badge">Agent API</span></div>
    <p class="stove-sys-card-desc">Loopback read-only API for AI agents to triage failures.</p>
    <div class="stove-sys-card-actions"><a href="21-mcp/">Reference</a></div>
  </div>
</div>
</div>

<div class="stove-cat-group" markdown="0">
<h3>🔌 Integration</h3>
<div class="stove-catalog">
  <div class="stove-sys-card">
    <div class="stove-sys-card-head"><strong>Bridge</strong><span class="stove-sys-card-badge">DI access</span></div>
    <p class="stove-sys-card-desc">Reach into supported JVM DI containers for setup and verification.</p>
    <div class="stove-sys-card-actions"><a href="10-bridge/">Reference</a></div>
  </div>
  <div class="stove-sys-card">
    <div class="stove-sys-card-head"><strong>Provided Instances</strong><span class="stove-sys-card-badge">No Docker</span></div>
    <p class="stove-sys-card-desc">Connect to existing infrastructure instead of Testcontainers.</p>
    <div class="stove-sys-card-actions"><a href="11-provided-instances/">Reference</a></div>
  </div>
  <div class="stove-sys-card">
    <div class="stove-sys-card-head"><strong>Provided Application</strong><span class="stove-sys-card-badge">Black-box</span></div>
    <p class="stove-sys-card-desc">Test an already-running app as a black-box target.</p>
    <div class="stove-sys-card-actions"><a href="19-provided-application/">Reference</a></div>
  </div>
  <div class="stove-sys-card">
    <div class="stove-sys-card-head"><strong>Multiple Systems</strong><span class="stove-sys-card-badge">Keyed</span></div>
    <p class="stove-sys-card-desc">Multiple named instances of the same system type (microservices).</p>
    <div class="stove-sys-card-actions"><a href="20-multiple-systems/">Reference</a></div>
  </div>
  <div class="stove-sys-card">
    <div class="stove-sys-card-head"><strong>Container AUT</strong><span class="stove-sys-card-badge">Polyglot</span></div>
    <p class="stove-sys-card-desc">Run any Docker image as the app under test (Go, Python, Node...).</p>
    <div class="stove-sys-card-actions"><a href="22-container/">Reference</a></div>
  </div>
</div>
</div>

## Every system shares the same shape

Once you configure one system, the lifecycle is the same for the rest.

<div class="stove-anatomy" markdown="0">
  <div class="stove-anatomy-code">
componentName <span class="anchor">1</span> {
  ComponentSystemOptions(
    container = ContainerOptions( <span class="anchor">2</span>
      registry = "docker.io",
      image = "component-image",
      tag = "1.2.3"
    ),
    configureExposedConfiguration = { cfg ->  <span class="anchor">3</span>
      listOf(
        "app.component.host=${cfg.host}",
        "app.component.port=${cfg.port}"
      )
    },
    cleanup = { ops -> ops.execute("...") }  <span class="anchor">4</span>
  )
}
  </div>
  <div class="stove-anatomy-notes">
    <div class="stove-note"><span class="stove-note-tag">1</span><strong>DSL block</strong> registers the system before the suite starts.</div>
    <div class="stove-note"><span class="stove-note-tag">2</span><strong>Runtime options</strong> pin a container image and tag, or switch to <code>.provided(...)</code> for existing infrastructure.</div>
    <div class="stove-note"><span class="stove-note-tag">3</span><strong>Exposed config</strong> converts runtime values (host, port, URL, credentials) into properties before the app boots.</div>
    <div class="stove-note"><span class="stove-note-tag">4</span><strong>Cleanup</strong> removes test data when Stove stops. Essential for shared infrastructure.</div>
  </div>
</div>

## Container mode vs Provided Instances

<div class="stove-compare" markdown="0">
  <div>
    <h4>Container mode (default)</h4>
    <p>Stove starts Testcontainers. Best for local development and CI runners with Docker.</p>

```kotlin
kafka {
  KafkaSystemOptions(
    container = KafkaContainerOptions(tag = "latest"),
    configureExposedConfiguration = { cfg -> listOf(...) }
  )
}
```

  </div>
  <div>
    <h4>Provided instance</h4>
    <p>Connect to existing infrastructure. Best when Docker isn't available or CI already runs shared Kafka/Postgres.</p>

```kotlin
kafka {
  KafkaSystemOptions.provided(
    bootstrapServers = "localhost:9092",
    configureExposedConfiguration = { cfg -> listOf(...) }
  )
}
```

  </div>
</div>

See [Provided Instances](11-provided-instances.md) for prefixing strategies that prevent collisions on shared infra.

## Migrations and cleanup

Databases and other stateful systems support migrations and per-system cleanup hooks. Migrations run during suite
startup before the application receives dependency configuration; cleanup runs when Stove stops at suite teardown.

```kotlin
class CreateTablesMigration : DatabaseMigration<PostgresSqlMigrationContext> {
  override val order: Int = 1
  override suspend fun execute(connection: PostgresSqlMigrationContext) {
    connection.operations.execute("CREATE TABLE orders ...")
  }
}

postgresql {
  PostgresqlOptions(
    cleanup = { ops -> ops.execute("DELETE FROM orders WHERE test = true") },
    configureExposedConfiguration = { cfg -> listOf(...) }
  ).migrations { register<CreateTablesMigration>() }
}
```

## Full-stack test, all systems at once

```kotlin hl_lines="5 9 16 23 28 33"
test("order flows across HTTP, DB, Kafka, ES, Redis") {
  stove {
    val orderId = UUID.randomUUID().toString()

    wiremock {
      mockPost("/payments", 200, PaymentResult(success = true).some())
    }

    http {
      postAndExpectBody<OrderResponse>(
        "/orders", body = CreateOrderRequest(orderId).some()
      ) { it.status shouldBe 201 }
    }

    couchbase {
      shouldGet<Order>("orders", orderId) { it.status shouldBe "CREATED" }
    }

    kafka {
      shouldBePublished<OrderCreatedEvent> { actual.orderId == orderId }
    }

    elasticsearch {
      shouldGet<Order>(index = "orders", key = orderId) {
        it.status shouldBe "CREATED"
      }
    }

    redis {
      client().connect().sync().get("order:$orderId") shouldNotBe null
    }
  }
}
```

For a full annotated walkthrough of this pattern, see [the order placement recipe](../recipes/order-flow.md).

## Where to next

<div class="grid cards" markdown>

-   :material-magic-staff: **Compose your stack**. [Setup Wizard](../wizard.md)

-   :material-book-multiple: **End-to-end scenarios**. [Recipes](../recipes/index.md)

-   :material-chart-timeline: **When a test fails**. [Observability story](../observability/when-it-fails.md)

-   :material-cog-outline: **Per-framework guides**. [Frameworks](../frameworks/index.md)

</div>
