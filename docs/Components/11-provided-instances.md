# Provided Instances (Testcontainer-less Mode)

Stove supports using externally provided infrastructure instances instead of testcontainers. This is particularly useful for:

- **CI/CD pipelines** with shared infrastructure
- **Reducing startup time** by reusing existing instances
- **Lower memory/CPU usage** by avoiding container overhead
- **Working with pre-configured environments**

## Overview

Instead of starting a testcontainer, you can configure Stove to connect to an existing instance using the `XxxProvided.runtime()` factory method and passing it to the system configuration.

## Core Concept: SystemRuntime

Stove uses a `SystemRuntime` sealed interface to represent how a system runs:

- **`ContainerRuntime`**: Manages a testcontainer
- **`ProvidedRuntime`**: Connects to an external instance

Both runtimes support a common `cleanup` function that executes before tests run.

## Usage Pattern

All systems follow the same pattern:

```kotlin
TestSystem()
  .with {
    // Option 1: Container-based (default)
    systemName(
      cleanup = { client -> /* cleanup logic */ }
    ) {
      SystemOptions(
        // System-specific options
        configureExposedConfiguration = { cfg -> listOf("property=${cfg.value}") }
      )
    }

    // Option 2: Provided instance
    systemName(
      providedRuntime = SystemProvided.runtime(
        // Connection parameters
        runMigrations = true,
        cleanup = { client -> /* cleanup logic */ }
      )
    ) {
      SystemOptions(
        // System-specific options
        configureExposedConfiguration = { cfg -> listOf("property=${cfg.value}") }
      )
    }
  }
  .run()
```

## Supported Systems

### Couchbase

```kotlin
// Container-based with cleanup
TestSystem()
  .with {
    couchbase(
      cleanup = { cluster ->
        cluster.query("DELETE FROM `myBucket` WHERE type = 'test'")
      }
    ) {
      CouchbaseSystemOptions(
        defaultBucket = "myBucket",
        configureExposedConfiguration = { cfg ->
          listOf(
            "couchbase.hosts=${cfg.hostsWithPort}",
            "couchbase.username=${cfg.username}",
            "couchbase.password=${cfg.password}"
          )
        }
      )
    }
  }
  .run()

// Provided instance
TestSystem()
  .with {
    couchbase(
      providedRuntime = CouchbaseProvided.runtime(
        connectionString = "couchbase://localhost:8091",
        username = "admin",
        password = "password",
        runMigrations = true,
        cleanup = { cluster ->
          cluster.query("DELETE FROM `myBucket` WHERE type = 'test'")
        }
      )
    ) {
      CouchbaseSystemOptions(
        defaultBucket = "myBucket",
        configureExposedConfiguration = { cfg ->
          listOf(
            "couchbase.hosts=${cfg.hostsWithPort}",
            "couchbase.username=${cfg.username}",
            "couchbase.password=${cfg.password}"
          )
        }
      )
    }
  }
  .run()
```

### Kafka

```kotlin
// Container-based
TestSystem()
  .with {
    kafka(
      cleanup = { adminClient ->
        adminClient.deleteTopics(listOf("test-topic")).all().get()
      }
    ) {
      KafkaSystemOptions(
        configureExposedConfiguration = { cfg ->
          listOf(
            "kafka.bootstrapServers=${cfg.bootstrapServers}",
            "kafka.interceptorClasses=${cfg.interceptorClass}"
          )
        }
      )
    }
  }
  .run()

// Provided instance
TestSystem()
  .with {
    kafka(
      providedRuntime = KafkaProvided.runtime(
        bootstrapServers = "localhost:9092",
        cleanup = { adminClient ->
          adminClient.deleteTopics(listOf("test-topic")).all().get()
        }
      )
    ) {
      KafkaSystemOptions(
        configureExposedConfiguration = { cfg ->
          listOf(
            "kafka.bootstrapServers=${cfg.bootstrapServers}",
            "kafka.interceptorClasses=${cfg.interceptorClass}"
          )
        }
      )
    }
  }
  .run()
```

### Redis

```kotlin
// Container-based
TestSystem()
  .with {
    redis(
      cleanup = { client ->
        client.connect().sync().flushdb()
      }
    ) {
      RedisOptions(
        configureExposedConfiguration = { cfg ->
          listOf(
            "redis.host=${cfg.host}",
            "redis.port=${cfg.port}",
            "redis.password=${cfg.password}"
          )
        }
      )
    }
  }
  .run()

// Provided instance
TestSystem()
  .with {
    redis(
      providedRuntime = RedisProvided.runtime(
        host = "localhost",
        port = 6379,
        password = "password",
        database = 8,
        cleanup = { client ->
          client.connect().sync().flushdb()
        }
      )
    ) {
      RedisOptions(
        configureExposedConfiguration = { cfg ->
          listOf(
            "redis.host=${cfg.host}",
            "redis.port=${cfg.port}",
            "redis.password=${cfg.password}"
          )
        }
      )
    }
  }
  .run()
```

### PostgreSQL

```kotlin
// Container-based
TestSystem()
  .with {
    postgresql(
      cleanup = { operations ->
        operations.execute("DELETE FROM users WHERE email LIKE '%@test.com'")
      }
    ) {
      PostgresqlOptions(
        databaseName = "testdb",
        configureExposedConfiguration = { cfg ->
          listOf(
            "spring.datasource.url=${cfg.jdbcUrl}",
            "spring.datasource.username=${cfg.username}",
            "spring.datasource.password=${cfg.password}"
          )
        }
      )
    }
  }
  .run()

// Provided instance
TestSystem()
  .with {
    postgresql(
      providedRuntime = PostgresqlProvided.runtime(
        jdbcUrl = "jdbc:postgresql://localhost:5432/testdb",
        host = "localhost",
        port = 5432,
        username = "postgres",
        password = "postgres",
        runMigrations = true,
        cleanup = { operations ->
          operations.execute("DELETE FROM users WHERE email LIKE '%@test.com'")
        }
      )
    ) {
      PostgresqlOptions(
        databaseName = "testdb",
        configureExposedConfiguration = { cfg ->
          listOf(
            "spring.datasource.url=${cfg.jdbcUrl}",
            "spring.datasource.username=${cfg.username}",
            "spring.datasource.password=${cfg.password}"
          )
        }
      )
    }
  }
  .run()
```

### MSSQL

```kotlin
// Container-based
TestSystem()
  .with {
    mssql(
      cleanup = { operations ->
        operations.execute("DELETE FROM Orders WHERE OrderDate < GETDATE() - 1")
      }
    ) {
      MsSqlOptions(
        applicationName = "stove-tests",
        databaseName = "testdb",
        userName = "sa",
        password = "YourStrong@Passw0rd",
        configureExposedConfiguration = { cfg ->
          listOf(
            "spring.datasource.url=${cfg.jdbcUrl}",
            "spring.datasource.username=${cfg.username}",
            "spring.datasource.password=${cfg.password}"
          )
        }
      )
    }
  }
  .run()

// Provided instance
TestSystem()
  .with {
    mssql(
      providedRuntime = MsSqlProvided.runtime(
        jdbcUrl = "jdbc:sqlserver://localhost:1433;databaseName=testdb",
        host = "localhost",
        port = 1433,
        username = "sa",
        password = "YourStrong@Passw0rd",
        runMigrations = true,
        cleanup = { operations ->
          operations.execute("DELETE FROM Orders WHERE OrderDate < GETDATE() - 1")
        }
      )
    ) {
      MsSqlOptions(
        applicationName = "stove-tests",
        databaseName = "testdb",
        userName = "sa",
        password = "YourStrong@Passw0rd",
        configureExposedConfiguration = { cfg ->
          listOf(
            "spring.datasource.url=${cfg.jdbcUrl}",
            "spring.datasource.username=${cfg.username}",
            "spring.datasource.password=${cfg.password}"
          )
        }
      )
    }
  }
  .run()
```

### MongoDB

```kotlin
// Container-based
TestSystem()
  .with {
    mongodb(
      cleanup = { client ->
        client.getDatabase("testdb").drop()
      }
    ) {
      MongodbSystemOptions(
        configureExposedConfiguration = { cfg ->
          listOf(
            "mongodb.uri=${cfg.connectionString}",
            "mongodb.host=${cfg.host}",
            "mongodb.port=${cfg.port}"
          )
        }
      )
    }
  }
  .run()

// Provided instance
TestSystem()
  .with {
    mongodb(
      providedRuntime = MongodbProvided.runtime(
        connectionString = "mongodb://localhost:27017",
        host = "localhost",
        port = 27017,
        cleanup = { client ->
          client.getDatabase("testdb").drop()
        }
      )
    ) {
      MongodbSystemOptions(
        configureExposedConfiguration = { cfg ->
          listOf(
            "mongodb.uri=${cfg.connectionString}",
            "mongodb.host=${cfg.host}",
            "mongodb.port=${cfg.port}"
          )
        }
      )
    }
  }
  .run()
```

### Elasticsearch

```kotlin
// Container-based
TestSystem()
  .with {
    elasticsearch(
      cleanup = { esClient ->
        esClient.indices().delete { it.index("test-*") }
      }
    ) {
      ElasticsearchSystemOptions(
        configureExposedConfiguration = { cfg ->
          listOf(
            "elasticsearch.host=${cfg.host}",
            "elasticsearch.port=${cfg.port}"
          )
        }
      )
    }
  }
  .run()

// Provided instance
TestSystem()
  .with {
    elasticsearch(
      providedRuntime = ElasticsearchProvided.runtime(
        host = "localhost",
        port = 9200,
        password = "", // Leave empty if security is disabled
        runMigrations = true,
        cleanup = { esClient ->
          esClient.indices().delete { it.index("test-*") }
        }
      )
    ) {
      ElasticsearchSystemOptions(
        configureExposedConfiguration = { cfg ->
          listOf(
            "elasticsearch.host=${cfg.host}",
            "elasticsearch.port=${cfg.port}"
          )
        }
      )
    }
  }
  .run()
```

## Cleanup Function

The `cleanup` parameter is available for both container-based and provided instance modes. It executes during `close()` before the system is stopped - this ensures cleanup runs after all tests have completed.

### Use Cases

1. **Clear test data** from previous runs
2. **Reset state** to a known baseline
3. **Delete test-specific records** that shouldn't persist

### Example with Container Mode and keepDependenciesRunning

The cleanup function is especially useful when using containers with `keepDependenciesRunning`:

```kotlin
TestSystem {
  keepDependenciesRunning()
}.with {
  couchbase(
    cleanup = { cluster ->
      // Clean test data between runs when reusing containers
      cluster.query("DELETE FROM `myBucket` WHERE type = 'test'")
    }
  ) {
    CouchbaseSystemOptions(
      defaultBucket = "myBucket",
      configureExposedConfiguration = { cfg ->
        listOf(
          "couchbase.hosts=${cfg.hostsWithPort}",
          "couchbase.username=${cfg.username}",
          "couchbase.password=${cfg.password}"
        )
      }
    )
  }
}.run()
```

## Migration Handling

When using provided instances, migrations are controlled by the `runMigrations` parameter in `ProvidedRuntime`:

- **`runMigrations = true` (default for databases)**: Migrations will run on every test execution
- **`runMigrations = false` (default for Kafka/Redis)**: Migrations are skipped

```kotlin
TestSystem()
  .with {
    postgresql(
      providedRuntime = PostgresqlProvided.runtime(
        // ... connection params
        runMigrations = false // Schema already exists
      )
    ) {
      PostgresqlOptions(
        configureExposedConfiguration = { cfg -> listOf(/* ... */) }
      )
    }
  }
  .run()
```

## Limitations

When using provided instances, some operations are not available:

- **`pause()`** - Cannot pause an external instance
- **`unpause()`** - Cannot unpause an external instance
- **`inspect()`** - Container inspection not available (MongoDB)

These methods will log a warning and return without effect when called on a provided instance.

## Complete Example

Here's a complete setup for a CI/CD pipeline using provided instances:

```kotlin
class TestSetup : AbstractProjectConfig() {
  override suspend fun beforeProject() {
    TestSystem()
      .with {
        httpClient {
          HttpClientSystemOptions(baseUrl = "http://localhost:8080")
        }
        bridge()
        couchbase(
          providedRuntime = CouchbaseProvided.runtime(
            connectionString = System.getenv("COUCHBASE_CONNECTION_STRING"),
            username = System.getenv("COUCHBASE_USERNAME"),
            password = System.getenv("COUCHBASE_PASSWORD"),
            runMigrations = true,
            cleanup = { cluster ->
              cluster.query("DELETE FROM `app-bucket` WHERE _type = 'test'")
            }
          )
        ) {
          CouchbaseSystemOptions(
            defaultBucket = "app-bucket",
            configureExposedConfiguration = { cfg ->
              listOf(
                "couchbase.hosts=${cfg.hostsWithPort}",
                "couchbase.username=${cfg.username}",
                "couchbase.password=${cfg.password}"
              )
            }
          )
        }
        kafka(
          providedRuntime = KafkaProvided.runtime(
            bootstrapServers = System.getenv("KAFKA_BOOTSTRAP_SERVERS"),
            cleanup = { admin ->
              val topics = admin.listTopics().names().get()
                .filter { it.startsWith("test-") }
              if (topics.isNotEmpty()) {
                admin.deleteTopics(topics).all().get()
              }
            }
          )
        ) {
          KafkaSystemOptions(
            configureExposedConfiguration = { cfg ->
              listOf(
                "kafka.bootstrapServers=${cfg.bootstrapServers}",
                "kafka.interceptorClasses=${cfg.interceptorClass}"
              )
            }
          )
        }
        spring(
          runner = { params ->
            com.example.Application.run(params)
          }
        )
      }
      .run()
  }

  override suspend fun afterProject() {
    TestSystem.stop()
  }
}
```
