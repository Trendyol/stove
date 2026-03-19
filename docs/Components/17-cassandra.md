# <span data-rn="underline" data-rn-color="#ff9800">Cassandra</span>

=== "Gradle"

    ``` kotlin
        dependencies {
            testImplementation(platform("com.trendyol:stove-bom:$version"))
            testImplementation("com.trendyol:stove-cassandra")
        }
    ```

## Configure

Once you've added the dependency, you'll have access to the `cassandra` function when configuring Stove.
This function configures the Cassandra Docker container that will be started for tests.

```kotlin hl_lines="4 6-10"
Stove()
  .with {
    cassandra {
      CassandraSystemOptions(
        keyspace = "my_keyspace",
        configureExposedConfiguration = { cfg ->
          listOf(
            "spring.cassandra.contact-points=${cfg.host}:${cfg.port}",
            "spring.cassandra.local-datacenter=${cfg.datacenter}",
            "spring.cassandra.keyspace-name=${cfg.keyspace}"
          )
        }
      )
    }
  }.run()
```

The `cfg` reference gives you access to the Cassandra container's connection details, which you can pass to your application.

### Container Options

Customize the Cassandra container version and configuration:

```kotlin
Stove()
  .with {
    cassandra {
      CassandraSystemOptions(
        keyspace = "my_keyspace",
        datacenter = "datacenter1",
        container = CassandraContainerOptions(
          registry = "docker.io",
          image = "cassandra",
          tag = "4.1",
          containerFn = { container ->
            // Additional container configuration
            container.withEnv("CASSANDRA_CLUSTER_NAME", "test-cluster")
          }
        ),
        configureExposedConfiguration = { cfg ->
          listOf(
            "spring.cassandra.contact-points=${cfg.host}:${cfg.port}",
            "spring.cassandra.local-datacenter=${cfg.datacenter}",
            "spring.cassandra.keyspace-name=${cfg.keyspace}"
          )
        }
      )
    }
  }.run()
```

### Cleanup

Use the `cleanup` lambda to truncate tables or delete data between test runs:

```kotlin
Stove()
  .with {
    cassandra {
      CassandraSystemOptions(
        keyspace = "my_keyspace",
        cleanup = { session ->
          session.execute("TRUNCATE my_keyspace.users")
          session.execute("TRUNCATE my_keyspace.events")
        },
        configureExposedConfiguration = { cfg ->
          listOf(
            "spring.cassandra.contact-points=${cfg.host}:${cfg.port}",
            "spring.cassandra.local-datacenter=${cfg.datacenter}",
            "spring.cassandra.keyspace-name=${cfg.keyspace}"
          )
        }
      )
    }
  }.run()
```

## Migrations

Stove provides a way to run CQL migrations before tests start.
Use this to create keyspaces, tables, indexes, and seed data.

```kotlin
class CreateKeyspaceMigration : CassandraMigration {
  override val order: Int = 1

  override suspend fun execute(connection: CassandraMigrationContext) {
    connection.session.execute(
      """
      CREATE KEYSPACE IF NOT EXISTS ${connection.options.keyspace}
        WITH replication = {'class': 'SimpleStrategy', 'replication_factor': 1}
      """.trimIndent()
    )
  }
}

class CreateTablesMigration : CassandraMigration {
  override val order: Int = 2

  override suspend fun execute(connection: CassandraMigrationContext) {
    connection.session.execute(
      """
      CREATE TABLE IF NOT EXISTS ${connection.options.keyspace}.users (
        id uuid PRIMARY KEY,
        name text,
        email text,
        created_at timestamp
      )
      """.trimIndent()
    )
  }
}
```

Register migrations in your Stove configuration:

```kotlin hl_lines="9-12"
Stove()
  .with {
    cassandra {
      CassandraSystemOptions(
        keyspace = "my_keyspace",
        configureExposedConfiguration = { cfg ->
          listOf("spring.cassandra.contact-points=${cfg.host}:${cfg.port}")
        }
      ).migrations {
        register<CreateKeyspaceMigration>()
        register<CreateTablesMigration>()
      }
    }
  }.run()
```

Migrations are executed in ascending `order` and are skipped on subsequent test runs (container reuse) unless `runMigrationsAlways` is enabled.

## Usage

### Executing CQL Statements

Execute any DDL or DML statement:

```kotlin hl_lines="3 8 13"
stove {
  cassandra {
    // Create a table
    shouldExecute(
      "CREATE TABLE IF NOT EXISTS my_keyspace.products (id uuid PRIMARY KEY, name text, price decimal)"
    )

    // Insert data
    shouldExecute(
      "INSERT INTO my_keyspace.products (id, name, price) VALUES (uuid(), 'Laptop', 999.99)"
    )

    // Delete data
    shouldExecute("DELETE FROM my_keyspace.products WHERE name = 'Laptop'")
  }
}
```

### Querying Data

Execute a CQL query and assert on the returned `ResultSet`:

```kotlin hl_lines="3 5"
stove {
  cassandra {
    shouldQuery("SELECT * FROM my_keyspace.products") { resultSet ->
      val rows = resultSet.all()
      rows.isNotEmpty() shouldBe true
      rows.first().getString("name") shouldBe "Laptop"
    }
  }
}
```

### Prepared Statements

Use prepared (bound) statements for parameterized queries:

```kotlin hl_lines="6 11"
stove {
  cassandra {
    // Prepare a statement using the raw session
    val prepared = session().prepare(
      "INSERT INTO my_keyspace.users (id, name, email) VALUES (?, ?, ?)"
    )
    val bound = prepared.bind(java.util.UUID.randomUUID(), "Jane Doe", "jane@example.com")

    // Execute the bound statement
    shouldExecute(bound)

    // Query with a bound statement
    val selectPrepared = session().prepare(
      "SELECT * FROM my_keyspace.users WHERE id = ?"
    )
    shouldQuery(selectPrepared.bind(bound.getUuid(0))) { resultSet ->
      val row = resultSet.one()
      row?.getString("name") shouldBe "Jane Doe"
    }
  }
}
```

### Direct Session Access

Access the raw `CqlSession` for advanced operations not covered by the DSL:

```kotlin hl_lines="4"
stove {
  cassandra {
    // Use session() for operations outside the DSL
    val result = session().execute("SELECT release_version FROM system.local")
    val version = result.one()?.getString("release_version")
    version shouldNotBe null
  }
}
```

### Pause and Unpause Container

Test resilience scenarios by pausing the Cassandra container:

```kotlin hl_lines="5 10"
stove {
  cassandra {
    // Verify database is reachable
    shouldQuery("SELECT * FROM my_keyspace.users") { it.all().size shouldBeGreaterThanOrEqual 0 }

    // Pause the container to simulate an outage
    pause()

    // Your application should handle the failure gracefully
    // ...

    // Restore the container
    unpause()

    // Verify recovery
    shouldQuery("SELECT * FROM my_keyspace.users") { it.all().size shouldBeGreaterThanOrEqual 0 }
  }
}
```

!!! note
    `pause()` and `unpause()` are only supported in container mode. They are ignored (with a warning) when using a [provided instance](#provided-instances).

## Complete Example

Here's a <span data-rn="highlight" data-rn-color="#00968855" data-rn-duration="800">complete end-to-end test</span>:

```kotlin hl_lines="7 12 22 30"
test("should create user via API and verify in Cassandra") {
  stove {
    val userName = "John Doe"
    val userEmail = "john@example.com"

    // Create user via API
    http {
      postAndExpectBody<UserResponse>(
        uri = "/users",
        body = CreateUserRequest(name = userName, email = userEmail).some()
      ) { response ->
        response.status shouldBe 201
      }
    }

    // Verify user event was published
    kafka {
      shouldBePublished<UserCreatedEvent>(atLeastIn = 10.seconds) {
        actual.name == userName && actual.email == userEmail
      }
    }

    // Verify user was stored in Cassandra
    cassandra {
      shouldQuery(
        "SELECT * FROM my_keyspace.users WHERE email = '$userEmail' ALLOW FILTERING"
      ) { resultSet ->
        val rows = resultSet.all()
        rows shouldHaveSize 1
        rows.first().getString("name") shouldBe userName
      }
    }
  }
}
```

## Provided Instances

Connect to an externally running Cassandra instance instead of a testcontainer.
This is useful when Docker is unavailable or you want to use a shared cluster.

```kotlin hl_lines="4-11"
Stove()
  .with {
    cassandra {
      CassandraSystemOptions.provided(
        host = "localhost",
        port = 9042,
        datacenter = "datacenter1",
        keyspace = "my_keyspace",
        runMigrations = true,
        cleanup = { session ->
          session.execute("TRUNCATE my_keyspace.users")
        },
        configureExposedConfiguration = { cfg ->
          listOf(
            "spring.cassandra.contact-points=${cfg.host}:${cfg.port}",
            "spring.cassandra.local-datacenter=${cfg.datacenter}",
            "spring.cassandra.keyspace-name=${cfg.keyspace}"
          )
        }
      ).migrations {
        register<CreateKeyspaceMigration>()
        register<CreateTablesMigration>()
      }
    }
  }.run()
```

See [Provided Instances](11-provided-instances.md) for more details on connecting to existing infrastructure.

## Spring Boot Integration

When using Spring Boot Data Cassandra, map the exposed configuration to Spring properties:

```kotlin
CassandraSystemOptions(
  keyspace = "my_keyspace",
  configureExposedConfiguration = { cfg ->
    listOf(
      "spring.cassandra.contact-points=${cfg.host}:${cfg.port}",
      "spring.cassandra.local-datacenter=${cfg.datacenter}",
      "spring.cassandra.keyspace-name=${cfg.keyspace}",
      "spring.cassandra.schema-action=CREATE_IF_NOT_EXISTS"
    )
  }
)
```

For `application.yml`-based configuration:

```yaml
spring:
  cassandra:
    contact-points: "${CASSANDRA_HOST}:${CASSANDRA_PORT}"
    local-datacenter: datacenter1
    keyspace-name: my_keyspace
```
