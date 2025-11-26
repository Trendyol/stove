# Provided Instances (Testcontainer-less Mode)

Stove supports using externally provided infrastructure instances instead of testcontainers. This is particularly useful for:

- **CI/CD pipelines** with shared infrastructure
- **Reducing startup time** by reusing existing instances
- **Lower memory/CPU usage** by avoiding container overhead
- **Working with pre-configured environments**

## Overview

Instead of starting a testcontainer, you can configure Stove to connect to an existing instance using the `.provided(...)` companion function on the options class itself.

## Core Concept

Each system's options class (e.g., `CouchbaseSystemOptions`, `PostgresqlOptions`) has a companion function called `provided(...)` that returns a specialized options subclass configured for external instances.

## Usage Pattern

All systems follow the same pattern:

```kotlin
TestSystem()
  .with {
    // Option 1: Container-based (default)
    systemName {
      SystemOptions(
        // System-specific options
        cleanup = { client -> /* cleanup logic */ },
        configureExposedConfiguration = { cfg -> listOf("property=${cfg.value}") }
      )
    }

    // Option 2: Provided instance using .provided() companion function
    systemName {
      SystemOptions.provided(
        // Connection parameters for external instance
        runMigrations = true,
        cleanup = { client -> /* cleanup logic */ },
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
    couchbase {
      CouchbaseSystemOptions(
        defaultBucket = "myBucket",
        cleanup = { cluster ->
          cluster.query("DELETE FROM `myBucket` WHERE type = 'test'")
        },
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
    couchbase {
      CouchbaseSystemOptions.provided(
        connectionString = "couchbase://localhost:8091",
        username = "admin",
        password = "password",
        defaultBucket = "myBucket",
        runMigrations = true,
        cleanup = { cluster ->
          cluster.query("DELETE FROM `myBucket` WHERE type = 'test'")
        },
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
    kafka {
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
    kafka {
      KafkaSystemOptions.provided(
        bootstrapServers = "localhost:9092",
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
    redis {
      RedisOptions(
        cleanup = { client ->
          client.connect().sync().flushdb()
        },
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
    redis {
      RedisOptions.provided(
        host = "localhost",
        port = 6379,
        password = "password",
        database = 8,
        cleanup = { client ->
          client.connect().sync().flushdb()
        },
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
    postgresql {
      PostgresqlOptions(
        databaseName = "testdb",
        cleanup = { operations ->
          operations.execute("DELETE FROM users WHERE email LIKE '%@test.com'")
        },
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
    postgresql {
      PostgresqlOptions.provided(
        jdbcUrl = "jdbc:postgresql://localhost:5432/testdb",
        host = "localhost",
        port = 5432,
        databaseName = "testdb",
        username = "postgres",
        password = "postgres",
        runMigrations = true,
        cleanup = { operations ->
          operations.execute("DELETE FROM users WHERE email LIKE '%@test.com'")
        },
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
    mssql {
      MsSqlOptions(
        applicationName = "stove-tests",
        databaseName = "testdb",
        userName = "sa",
        password = "YourStrong@Passw0rd",
        cleanup = { operations ->
          operations.execute("DELETE FROM Orders WHERE OrderDate < GETDATE() - 1")
        },
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
    mssql {
      MsSqlOptions.provided(
        jdbcUrl = "jdbc:sqlserver://localhost:1433;databaseName=testdb",
        host = "localhost",
        port = 1433,
        databaseName = "testdb",
        username = "sa",
        password = "YourStrong@Passw0rd",
        runMigrations = true,
        cleanup = { operations ->
          operations.execute("DELETE FROM Orders WHERE OrderDate < GETDATE() - 1")
        },
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
    mongodb {
      MongodbSystemOptions(
        cleanup = { client ->
          client.getDatabase("testdb").drop()
        },
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
    mongodb {
      MongodbSystemOptions.provided(
        connectionString = "mongodb://localhost:27017",
        host = "localhost",
        port = 27017,
        cleanup = { client ->
          client.getDatabase("testdb").drop()
        },
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
    elasticsearch {
      ElasticsearchSystemOptions(
        cleanup = { esClient ->
          esClient.indices().delete { it.index("test-*") }
        },
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
    elasticsearch {
      ElasticsearchSystemOptions.provided(
        host = "localhost",
        port = 9200,
        password = "", // Leave empty if security is disabled
        runMigrations = true,
        cleanup = { esClient ->
          esClient.indices().delete { it.index("test-*") }
        },
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
  couchbase {
    CouchbaseSystemOptions(
      defaultBucket = "myBucket",
      cleanup = { cluster ->
        // Clean test data between runs when reusing containers
        cluster.query("DELETE FROM `myBucket` WHERE type = 'test'")
      },
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

When using provided instances, migrations are controlled by the `runMigrations` parameter in the `.provided()` function:

- **`runMigrations = true` (default for databases)**: Migrations will run on every test execution
- **`runMigrations = false` (default for Kafka/Redis)**: Migrations are skipped

```kotlin
TestSystem()
  .with {
    postgresql {
      PostgresqlOptions.provided(
        jdbcUrl = "jdbc:postgresql://localhost:5432/mydb",
        host = "localhost",
        port = 5432,
        databaseName = "mydb",
        username = "user",
        password = "pass",
        runMigrations = false, // Schema already exists
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
- **`inspect()`** - Container inspection not available

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
        couchbase {
          CouchbaseSystemOptions.provided(
            connectionString = System.getenv("COUCHBASE_CONNECTION_STRING"),
            username = System.getenv("COUCHBASE_USERNAME"),
            password = System.getenv("COUCHBASE_PASSWORD"),
            defaultBucket = "app-bucket",
            runMigrations = true,
            cleanup = { cluster ->
              cluster.query("DELETE FROM `app-bucket` WHERE _type = 'test'")
            },
            configureExposedConfiguration = { cfg ->
              listOf(
                "couchbase.hosts=${cfg.hostsWithPort}",
                "couchbase.username=${cfg.username}",
                "couchbase.password=${cfg.password}"
              )
            }
          )
        }
        kafka {
          KafkaSystemOptions.provided(
            bootstrapServers = System.getenv("KAFKA_BOOTSTRAP_SERVERS"),
            configureExposedConfiguration = { cfg ->
              listOf(
                "kafka.bootstrapServers=${cfg.bootstrapServers}",
                "kafka.interceptorClasses=${cfg.interceptorClass}"
              )
            }
          )
        }
        springBoot(
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

## Test Isolation with Shared Infrastructure

!!! warning "Critical: Prevent Test Run Collisions"

    When using provided instances (shared infrastructure), **multiple test runs can interfere with each other** if they use the same resource names. This is especially important in CI/CD pipelines where parallel builds may run against the same infrastructure.

### The Problem

Consider this scenario:
- Build #1 creates records in `orders` table
- Build #2 starts while Build #1 is still running
- Build #2 reads Build #1's test data → **Test failures!**
- Both builds try to create the same Kafka topic → **Conflicts!**

### The Solution: Unique Resource Prefixes

Generate unique prefixes for each test run and use them for all resource names:

```kotlin
object TestRunContext {
    // Unique prefix for this test run
    val runId: String = System.getenv("CI_JOB_ID") 
        ?: System.getenv("BUILD_NUMBER")
        ?: UUID.randomUUID().toString().take(8)
    
    // Resource names with unique prefixes
    val databaseName = "testdb_$runId"
    val topicPrefix = "test_${runId}_"
    val indexPrefix = "test_${runId}_"
    val bucketPrefix = "test_${runId}_"
    val cacheKeyPrefix = "test:$runId:"
}
```

### Implementation by System

#### PostgreSQL / MSSQL - Unique Database

```kotlin
TestSystem()
    .with {
        postgresql {
            PostgresqlOptions.provided(
                jdbcUrl = "jdbc:postgresql://shared-db:5432/${TestRunContext.databaseName}",
                host = "shared-db",
                port = 5432,
                databaseName = TestRunContext.databaseName,
                username = "postgres",
                password = "postgres",
                runMigrations = true,  // Creates tables in unique database
                cleanup = { ops ->
                    // Optional: cleanup is less critical with unique database
                    ops.execute("DROP SCHEMA public CASCADE; CREATE SCHEMA public;")
                },
                configureExposedConfiguration = { cfg ->
                    listOf("spring.datasource.url=${cfg.jdbcUrl}")
                }
            )
        }
        springBoot(
            withParameters = listOf(
                "spring.datasource.url=jdbc:postgresql://shared-db:5432/${TestRunContext.databaseName}"
            )
        )
    }
```

!!! tip "Database Creation"
    You can create the database using Stove's migration system:
    ```kotlin
    class CreateDatabaseMigration : DatabaseMigration<PostgresSqlMigrationContext> {
        override val order: Int = 0  // Run first
        
        override suspend fun execute(connection: PostgresSqlMigrationContext) {
            connection.operations.execute(
                "CREATE DATABASE IF NOT EXISTS ${TestRunContext.databaseName}"
            )
        }
    }
    ```

!!! tip "Multiple Databases"
    If your application uses multiple databases in production (e.g., separate databases for users, orders, analytics), you can create all of them via migrations and expose separate connection URLs:
    
    ```kotlin
    configureExposedConfiguration = { cfg ->
        val baseUrl = "jdbc:postgresql://${cfg.host}:${cfg.port}"
        listOf(
            "db.users.url=$baseUrl/users_${TestRunContext.runId}",
            "db.orders.url=$baseUrl/orders_${TestRunContext.runId}",
            "db.analytics.url=$baseUrl/analytics_${TestRunContext.runId}",
            // ... common credentials
        )
    }
    ```
    
    See [PostgreSQL - Multiple Databases](06-postgresql.md#multiple-databases) for a complete guide.

#### Kafka - Unique Topic Prefix

```kotlin
TestSystem()
    .with {
        kafka {
            KafkaSystemOptions.provided(
                bootstrapServers = "shared-kafka:9092",
                topicSuffixes = TopicSuffixes(
                    // These are suffixes for error/retry topics
                    error = ".error",
                    retry = ".retry"
                ),
                cleanup = { admin ->
                    // Delete only topics with our prefix
                    val ourTopics = admin.listTopics().names().get()
                        .filter { it.startsWith(TestRunContext.topicPrefix) }
                    if (ourTopics.isNotEmpty()) {
                        admin.deleteTopics(ourTopics).all().get()
                    }
                },
                configureExposedConfiguration = { cfg ->
                    listOf(
                        "kafka.bootstrapServers=${cfg.bootstrapServers}",
                        "kafka.topicPrefix=${TestRunContext.topicPrefix}"
                    )
                }
            )
        }
        springBoot(
            withParameters = listOf(
                // Application uses this prefix for all topic names
                "kafka.topic.orders=${TestRunContext.topicPrefix}orders",
                "kafka.topic.payments=${TestRunContext.topicPrefix}payments",
                "kafka.topic.notifications=${TestRunContext.topicPrefix}notifications"
            )
        )
    }
```

#### Elasticsearch - Unique Index Prefix

```kotlin
TestSystem()
    .with {
        elasticsearch {
            ElasticsearchSystemOptions.provided(
                host = "shared-elasticsearch",
                port = 9200,
                password = "",
                runMigrations = true,
                cleanup = { esClient ->
                    // Delete only indices with our prefix
                    esClient.indices().delete { 
                        it.index("${TestRunContext.indexPrefix}*") 
                    }
                },
                configureExposedConfiguration = { cfg ->
                    listOf(
                        "elasticsearch.host=${cfg.host}",
                        "elasticsearch.indexPrefix=${TestRunContext.indexPrefix}"
                    )
                }
            )
        }
        springBoot(
            withParameters = listOf(
                "elasticsearch.index.products=${TestRunContext.indexPrefix}products",
                "elasticsearch.index.orders=${TestRunContext.indexPrefix}orders"
            )
        )
    }
```

#### Couchbase - Unique Document Prefix or Scope

```kotlin
TestSystem()
    .with {
        couchbase {
            CouchbaseSystemOptions.provided(
                connectionString = "couchbase://shared-couchbase:8091",
                username = "admin",
                password = "password",
                defaultBucket = "shared-bucket",
                runMigrations = true,
                cleanup = { cluster ->
                    // Delete only documents with our prefix
                    cluster.query(
                        "DELETE FROM `shared-bucket` WHERE META().id LIKE '${TestRunContext.bucketPrefix}%'"
                    )
                },
                configureExposedConfiguration = { cfg ->
                    listOf(
                        "couchbase.documentPrefix=${TestRunContext.bucketPrefix}"
                    )
                }
            )
        }
        springBoot(
            withParameters = listOf(
                "couchbase.documentPrefix=${TestRunContext.bucketPrefix}"
            )
        )
    }
```

#### MongoDB - Unique Database or Collection Prefix

```kotlin
TestSystem()
    .with {
        mongodb {
            MongodbSystemOptions.provided(
                connectionString = "mongodb://shared-mongo:27017",
                host = "shared-mongo",
                port = 27017,
                cleanup = { client ->
                    // Drop our unique database
                    client.getDatabase(TestRunContext.databaseName).drop()
                },
                configureExposedConfiguration = { cfg ->
                    listOf(
                        "mongodb.database=${TestRunContext.databaseName}"
                    )
                }
            )
        }
        springBoot(
            withParameters = listOf(
                "spring.data.mongodb.database=${TestRunContext.databaseName}"
            )
        )
    }
```

#### Redis - Unique Key Prefix or Database Number

```kotlin
TestSystem()
    .with {
        redis {
            // Use unique database number (0-15) or key prefix
            val redisDb = (TestRunContext.runId.hashCode() and 0xF)  // 0-15
            
            RedisOptions.provided(
                host = "shared-redis",
                port = 6379,
                password = "",
                database = redisDb,
                cleanup = { client ->
                    // Flush only our database
                    client.connect().sync().flushdb()
                },
                configureExposedConfiguration = { cfg ->
                    listOf(
                        "spring.redis.database=$redisDb"
                    )
                }
            )
        }
    }
```

### Complete CI/CD Example

```kotlin
object TestRunContext {
    val runId: String = System.getenv("CI_JOB_ID") 
        ?: System.getenv("GITHUB_RUN_ID")
        ?: System.getenv("BUILD_NUMBER")
        ?: UUID.randomUUID().toString().take(8)
    
    val databaseName = "test_$runId"
    val topicPrefix = "test_${runId}_"
    val indexPrefix = "test_${runId}_"
    val keyPrefix = "test:$runId:"
    
    init {
        println("Test Run ID: $runId")
        println("Database: $databaseName")
        println("Topic Prefix: $topicPrefix")
    }
}

class TestConfig : AbstractProjectConfig() {
    override suspend fun beforeProject() {
        TestSystem()
            .with {
                postgresql {
                    PostgresqlOptions.provided(
                        jdbcUrl = "jdbc:postgresql://db:5432/${TestRunContext.databaseName}",
                        databaseName = TestRunContext.databaseName,
                        // ... other config
                    )
                }
                kafka {
                    KafkaSystemOptions.provided(
                        bootstrapServers = "kafka:9092",
                        cleanup = { admin ->
                            val topics = admin.listTopics().names().get()
                                .filter { it.startsWith(TestRunContext.topicPrefix) }
                            if (topics.isNotEmpty()) admin.deleteTopics(topics).all().get()
                        },
                        // ... other config
                    )
                }
                elasticsearch {
                    ElasticsearchSystemOptions.provided(
                        host = "elasticsearch",
                        port = 9200,
                        cleanup = { es ->
                            es.indices().delete { it.index("${TestRunContext.indexPrefix}*") }
                        },
                        // ... other config
                    )
                }
                springBoot(
                    runner = { params -> myApp.run(params) },
                    withParameters = listOf(
                        "spring.datasource.url=jdbc:postgresql://db:5432/${TestRunContext.databaseName}",
                        "kafka.topic.orders=${TestRunContext.topicPrefix}orders",
                        "elasticsearch.index.products=${TestRunContext.indexPrefix}products"
                    )
                )
            }
            .run()
    }
    
    override suspend fun afterProject() {
        TestSystem.stop()
        // Resources cleaned up by cleanup functions
    }
}
```

### Best Practices for Test Isolation

| Practice | Description |
|----------|-------------|
| **Use CI Job ID** | Most CI systems provide unique job/build IDs - use them |
| **Prefix everything** | Database names, topics, indices, keys - all should be unique |
| **Clean up after** | Use cleanup functions to remove test data |
| **Short prefixes** | Keep prefixes short but unique (8 chars usually enough) |
| **Log the prefix** | Print the run ID at test start for debugging |
| **Application support** | Your app must read resource names from configuration |

### Debugging Isolation Issues

If tests fail intermittently in CI:

1. **Check for hardcoded names:**
   ```kotlin
   // ❌ Bad - hardcoded
   val topic = "orders"
   
   // ✅ Good - configurable
   val topic = config.getString("kafka.topic.orders")
   ```

2. **Verify cleanup runs:**
   ```kotlin
   cleanup = { admin ->
       println("Cleaning up topics with prefix: ${TestRunContext.topicPrefix}")
       // ... cleanup code
   }
   ```

3. **Check parallel job interference:**
   ```bash
   # In CI logs, look for overlapping run IDs
   grep "Test Run ID" build-*.log
   ```
