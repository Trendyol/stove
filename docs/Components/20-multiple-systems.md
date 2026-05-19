# Multiple Systems (Keyed)

Default: one instance per system type. Need more? Register multiple instances of the same type with typed keys. Useful for microservice integration, multiple databases, multi-cluster Kafka, cross-service verification.

<div class="stove-tldr" markdown>
<span class="stove-tldr-title">In 30 seconds</span>
Define keys as Kotlin <code>object</code>s implementing <code>SystemKey</code>. Pass the key as the first argument to any system DSL: <code>postgresql(AppDb) { }</code>, <code>kafka(MainCluster) { }</code>, <code>httpClient(PaymentService) { }</code>. Use the same key inside <code>stove { }</code> to target that instance.
</div>

## Define keys

Keys are Kotlin singletons implementing `SystemKey`. They show up in reports and traces, so name them after the *role* the instance plays.

```kotlin
object AppDb : SystemKey
object AnalyticsDb : SystemKey

object MainCluster : SystemKey
object AuditCluster : SystemKey

object PaymentService : SystemKey
object InventoryService : SystemKey
```

!!! tip "Why objects, not strings?"
    Typed keys catch typos at compile time and let IDEs autocomplete usages. Strings would silently bind to "new" instances if misspelled.

## Register keyed instances

Pass the key as the first arg. Everything else is identical to the single-instance API. Default + keyed instances coexist.

```kotlin
Stove().with {
  // Default, unkeyed Postgres (optional)
  postgresql {
    PostgresqlOptions(/* ... */)
  }

  // Keyed instances. separate containers, ports, state
  postgresql(AppDb) {
    PostgresqlOptions(
      databaseName = "app",
      configureExposedConfiguration = { cfg ->
        listOf("app.datasource.url=${cfg.jdbcUrl}")
      }
    )
  }

  postgresql(AnalyticsDb) {
    PostgresqlOptions(
      databaseName = "analytics",
      configureExposedConfiguration = { cfg ->
        listOf("analytics.datasource.url=${cfg.jdbcUrl}")
      }
    )
  }

  httpClient(PaymentService) {
    HttpClientSystemOptions(baseUrl = "https://pay.internal")
  }

  httpClient(InventoryService) {
    HttpClientSystemOptions(baseUrl = "https://inv.internal")
  }

  springBoot(runner = { params -> com.app.run(params) })
}.run()
```

## Use keys in tests

Same DSL, just pass the key:

```kotlin
stove {
  postgresql(AppDb) {
    shouldExecute("INSERT INTO users(id) VALUES ('u1')")
  }

  postgresql(AnalyticsDb) {
    shouldQuery<EventRow>("SELECT * FROM events") { it shouldHaveSize 0 }
  }

  httpClient(PaymentService) {
    get<PaymentResponse>("/health") { it.status shouldBe "OK" }
  }

  httpClient(InventoryService) {
    post<InventoryResponse>("/reserve", body) { it.status shouldBe 200 }
  }
}
```

## Supported systems

All systems support keyed registration:

`postgresql · mysql · mssql · mongodb · couchbase · cassandra · redis · elasticsearch · kafka · httpClient · grpc · grpcMock · wiremock`

## Combine with `providedApplication`

For microservice integration tests where you don't own all the AUTs:

```kotlin
Stove().with {
  // Your service runs locally
  springBoot(runner = { params -> com.app.run(params) })

  // Upstream / downstream services already running
  httpClient(PaymentService) {
    HttpClientSystemOptions(baseUrl = "https://pay.staging")
  }
  httpClient(InventoryService) {
    HttpClientSystemOptions(baseUrl = "https://inv.staging")
  }
}.run()
```

## Reporting

Keyed instances appear in failure reports with their key name, e.g. `kafka[MainCluster] shouldBePublished ...`. No more guessing which Kafka the assertion targeted.

## When NOT to use keys

<div class="stove-pair" markdown="0">
  <div class="stove-do">
**One key per logical role.**

```kotlin
object PrimaryDb : SystemKey
object ReadReplica : SystemKey

postgresql(PrimaryDb) { /* ... */ }
postgresql(ReadReplica) { /* ... */ }
```

  </div>
  <div class="stove-dont">
**Don't key for sharding or partitioning.** That's an app concern.

```kotlin
object Shard1 : SystemKey
object Shard2 : SystemKey
object Shard3 : SystemKey
// ... 256 more
```

Tests don't model production sharding; they verify the *behavior* one shard at a time.
  </div>
</div>

## Related

- [Multi-system order recipe](../recipes/order-flow.md)
- [Provided Application](19-provided-application.md) for black-box upstream services
- [Provided Instances](11-provided-instances.md) for shared CI infrastructure
