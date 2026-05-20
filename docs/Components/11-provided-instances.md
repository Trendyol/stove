# Provided Instances

Connect Stove to **existing infrastructure** such as shared CI databases, dev clusters, or staging brokers instead of spinning Testcontainers. The registered system DSL and assertions stay the same; infrastructure lifecycle and cleanup become your responsibility.

<div class="stove-tldr" markdown>
<span class="stove-tldr-title">In 30 seconds</span>
Each listed dependency system options class ships a <code>.provided(...)</code> factory. Swap <code>SystemOptions(...)</code> for <code>SystemOptions.provided(...)</code>, supply connection details, and Stove connects to your infrastructure instead of starting a container. For shared infra, prefix every resource (schemas, topics, indexes, keys) with a unique run ID to prevent collisions.
</div>

## The pattern

<div class="stove-compare" markdown="0">
  <div>
    <h4>Container mode (default)</h4>

```kotlin
postgresql {
  PostgresqlOptions(
    container = PostgresqlContainerOptions(tag = "16"),
    configureExposedConfiguration = { cfg ->
      listOf("spring.datasource.url=${cfg.jdbcUrl}")
    }
  )
}
```

  </div>
  <div>
    <h4>Provided instance</h4>

```kotlin
postgresql {
  PostgresqlOptions.provided(
    jdbcUrl = "jdbc:postgresql://shared-db:5432/test",
    username = "test",
    password = "test",
    configureExposedConfiguration = { cfg ->
      listOf("spring.datasource.url=${cfg.jdbcUrl}")
    }
  )
}
```

  </div>
</div>

The registration shape stays identical, but the operational contract changes: Stove does not create, isolate, pause, or destroy a provided dependency.

## Supported systems

The built-in dependency systems below support provided instances. Signatures are similar; check the specific reference page for full details and cleanup support.

| System | Factory | Required args |
|---|---|---|
| PostgreSQL | `PostgresqlOptions.provided` | `jdbcUrl`, `username`, `password` |
| MySQL | `MySqlOptions.provided` | `jdbcUrl`, `username`, `password` |
| MSSQL | `MsSqlOptions.provided` | `jdbcUrl`, `username`, `password` |
| MongoDB | `MongodbSystemOptions.provided` | `connectionString` |
| Couchbase | `CouchbaseSystemOptions.provided` | `bucketName`, `hostsWithPort`, `username`, `password` |
| Cassandra | `CassandraSystemOptions.provided` | `contactPoints`, `keyspace` |
| Redis | `RedisSystemOptions.provided` | `url` |
| Elasticsearch | `ElasticsearchSystemOptions.provided` | `url` |
| Kafka | `KafkaSystemOptions.provided` | `bootstrapServers` |

Each accepts the same `configureExposedConfiguration` and (where applicable) `cleanup` lambdas as the container mode.

## Cleanup

Container mode isolates most state by removing containers when Stove stops. Provided instances persist across runs, so you must clean test data yourself. Every provided options class accepts a `cleanup` lambda that runs on suite teardown.

```kotlin
postgresql {
  PostgresqlOptions.provided(
    jdbcUrl = TestRunContext.jdbcUrl,
    cleanup = { ops ->
      ops.execute("DROP SCHEMA IF EXISTS ${TestRunContext.schemaName} CASCADE")
    },
    configureExposedConfiguration = { cfg -> listOf(/* ... */) }
  )
}

kafka {
  KafkaSystemOptions.provided(
    bootstrapServers = "shared-kafka:9092",
    cleanup = { admin ->
      val testTopics = admin.listTopics().names().get()
        .filter { it.startsWith(TestRunContext.topicPrefix) }
      if (testTopics.isNotEmpty()) {
        admin.deleteTopics(testTopics).all().get()
      }
    },
    configureExposedConfiguration = { cfg -> listOf(/* ... */) }
  )
}
```

## Migrations

By default, migrations run only in container mode (you don't want test migrations smashing shared schemas). Opt-in for provided:

```kotlin
postgresql {
  PostgresqlOptions.provided(
    jdbcUrl = "...",
    runMigrations = true,   // default: false for provided
    /* ... */
  ).migrations { register<CreateTablesMigration>() }
}
```

Apply with care on shared infra.

## Limitations

| Feature | Container | Provided |
|---|---|---|
| `pause()` / `unpause()` | ✓ | ✗ |
| `inspect()` for container internals | ✓ | ✗ |
| Automatic cleanup | ✓ | manual via `cleanup` lambda |
| `keepDependenciesRunning` | yes (container survives) | n/a (you didn't start it) |

<a id="test-isolation-with-shared-infrastructure"></a>

## Shared infrastructure: isolation pattern

Parallel CI runs against the same Postgres/Kafka collide without isolation. Solution: **unique resource prefix per run.**

```kotlin
object TestRunContext {
  val runId: String = System.getenv("CI_JOB_ID")
    ?: UUID.randomUUID().toString().take(8)

  val schemaName  = "test_$runId"
  val topicPrefix = "test_${runId}_"
  val indexPrefix = "test_${runId}_"
  val keyPrefix   = "test:${runId}:"
}
```

Wire it everywhere:

=== "PostgreSQL"

    ```kotlin
    postgresql {
      PostgresqlOptions.provided(
        jdbcUrl = "jdbc:postgresql://shared-db:5432/test?currentSchema=${TestRunContext.schemaName}",
        cleanup = { ops ->
          ops.execute("DROP SCHEMA IF EXISTS ${TestRunContext.schemaName} CASCADE")
        },
        configureExposedConfiguration = { cfg -> listOf(
          "spring.datasource.url=${cfg.jdbcUrl}"
        ) }
      )
    }
    ```

=== "Kafka"

    ```kotlin
    springBoot(withParameters = listOf(
      "app.kafka.topic.orders=${TestRunContext.topicPrefix}orders",
      "app.kafka.topic.audit=${TestRunContext.topicPrefix}audit"
    ))

    kafka {
      KafkaSystemOptions.provided(
        bootstrapServers = "shared-kafka:9092",
        cleanup = { admin ->
          val topics = admin.listTopics().names().get()
            .filter { it.startsWith(TestRunContext.topicPrefix) }
          if (topics.isNotEmpty()) admin.deleteTopics(topics).all().get()
        },
        configureExposedConfiguration = { cfg -> listOf(/* ... */) }
      )
    }
    ```

=== "Elasticsearch"

    ```kotlin
    springBoot(withParameters = listOf(
      "app.es.index.products=${TestRunContext.indexPrefix}products"
    ))

    elasticsearch {
      ElasticsearchSystemOptions.provided(
        url = "http://shared-es:9200",
        cleanup = { client ->
          client.indices().delete { it.index("${TestRunContext.indexPrefix}*") }
        },
        configureExposedConfiguration = { cfg -> listOf(/* ... */) }
      )
    }
    ```

=== "Redis"

    ```kotlin
    springBoot(withParameters = listOf(
      "app.cache.prefix=${TestRunContext.keyPrefix}"
    ))

    redis {
      RedisSystemOptions.provided(
        url = "redis://shared-redis:6379",
        cleanup = { client ->
          val keys = client.keys("${TestRunContext.keyPrefix}*")
          if (keys.isNotEmpty()) client.del(*keys.toTypedArray())
        },
        configureExposedConfiguration = { cfg -> listOf(/* ... */) }
      )
    }
    ```

=== "MongoDB / Couchbase / Cassandra"

    Same idea: prefix collection / bucket / table names, drop in `cleanup`. See each system's reference for the exact API.

### Isolation cheat sheet

| Symptom | Likely cause | Fix |
|---|---|---|
| Parallel runs read each other's rows | Same schema/topic/index name | Add `runId` to resource prefix |
| Cleanup leaves orphans | Cleanup ran but missed a prefix | Log `TestRunContext.runId` at suite start; reconcile during nightly job |
| Run hangs forever | App writing to prefixed topics that don't exist | Enable broker-level auto-create or pre-create topics in setup |

## Complete CI/CD example

```kotlin
class CIE2EConfig : AbstractProjectConfig() {
  override suspend fun beforeProject() {
    Stove().with {
      httpClient {
        HttpClientSystemOptions(baseUrl = "http://localhost:8080")
      }

      postgresql {
        PostgresqlOptions.provided(
          jdbcUrl = "jdbc:postgresql://shared-db:5432/test?currentSchema=${TestRunContext.schemaName}",
          username = System.getenv("PG_USER"),
          password = System.getenv("PG_PASS"),
          cleanup = { ops ->
            ops.execute("DROP SCHEMA IF EXISTS ${TestRunContext.schemaName} CASCADE")
          },
          configureExposedConfiguration = { cfg -> listOf(
            "spring.datasource.url=${cfg.jdbcUrl}",
            "spring.datasource.username=${cfg.username}",
            "spring.datasource.password=${cfg.password}"
          ) }
        )
      }

      kafka {
        KafkaSystemOptions.provided(
          bootstrapServers = System.getenv("KAFKA_BOOTSTRAP"),
          cleanup = { admin ->
            admin.listTopics().names().get()
              .filter { it.startsWith(TestRunContext.topicPrefix) }
              .takeIf { it.isNotEmpty() }
              ?.let { admin.deleteTopics(it).all().get() }
          },
          configureExposedConfiguration = { cfg -> listOf(
            "spring.kafka.bootstrap-servers=${cfg.bootstrapServers}"
          ) }
        )
      }

      springBoot(
        runner = { params -> com.app.run(params) },
        withParameters = listOf(
          "app.kafka.topic.orders=${TestRunContext.topicPrefix}orders"
        )
      )
    }.run()
  }

  override suspend fun afterProject() = Stove.stop()
}
```

## Best practices

- :white_check_mark: Always log `TestRunContext.runId` at suite start for forensic debugging
- :white_check_mark: Reconcile orphans nightly (cleanup hooks can fail mid-run)
- :white_check_mark: Use stable `CI_JOB_ID` over random when available. Easier to trace
- :x: Don't share a single schema/topic across runs
- :x: Don't skip cleanup hooks "for speed"; shared-state debt compounds

## Related

- [Best Practices · shared infra](../best-practices.md#shared-infra-isolation-ci)
- [Provided Application](19-provided-application.md) for black-box smoke testing
- Per-system reference: [PostgreSQL](06-postgresql.md), [Kafka](02-kafka.md), [MongoDB](07-mongodb.md), [Redis](09-redis.md), ...
