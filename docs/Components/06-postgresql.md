# PostgreSQL

Real Postgres in a container (or wired to existing infra). Migrations, type-safe queries, pause/unpause for fault-injection tests.

<a class="open-in-wizard" data-sys="postgresql">Open in setup wizard</a>

<!--{wizard:snippet id=sys.postgresql parts=gradle,configure,test}-->

<div class="stove-tldr" markdown>
<span class="stove-tldr-title">In 30 seconds</span>
Add <code>stove-postgres</code>. Register <code>postgresql { PostgresqlOptions(...) }</code> in <code>Stove().with</code>. Hand connection details to your app via <code>configureExposedConfiguration</code>. Run migrations through <code>.migrations { register&lt;T&gt;() }</code>. Query with <code>shouldQuery&lt;T&gt;</code>; execute DDL/DML with <code>shouldExecute</code>.
</div>

## Configure

```kotlin
Stove().with {
  postgresql {
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
}.run()
```

`cfg` exposes `host`, `port`, `database`, `username`, `password`, `jdbcUrl`. Mirror your app's property names.

## Migrations

```kotlin
class InitialMigration : DatabaseMigration<PostgresSqlMigrationContext> {
  override val order: Int = 1

  override suspend fun execute(connection: PostgresSqlMigrationContext) {
    connection.operations.execute(
      """
      CREATE TABLE IF NOT EXISTS users (
        id serial PRIMARY KEY,
        name VARCHAR(100) NOT NULL,
        email VARCHAR(100) NOT NULL UNIQUE,
        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
      );
      """.trimIndent()
    )
  }
}
```

Register:

```kotlin
postgresql {
  PostgresqlOptions(/* ... */).migrations {
    register<InitialMigration>()
  }
}
```

## Query DSL

### Execute DDL/DML

```kotlin
stove {
  postgresql {
    shouldExecute(
      """
      CREATE TABLE IF NOT EXISTS products (
        id serial PRIMARY KEY,
        name VARCHAR(100) NOT NULL,
        price DECIMAL(10, 2) NOT NULL
      )
      """.trimIndent()
    )

    shouldExecute("INSERT INTO products (name, price) VALUES ('Laptop', 999.99)")
    shouldExecute("UPDATE products SET price = 899.99 WHERE name = 'Laptop'")
    shouldExecute("DELETE FROM products WHERE price = 0")
  }
}
```

### Type-safe query

```kotlin
data class Product(val id: Long, val name: String, val price: Double)

stove {
  postgresql {
    shouldQuery<Product>(
      query = "SELECT * FROM products WHERE price > 500",
      mapper = { row ->
        Product(
          id = row.long("id"),
          name = row.string("name"),
          price = row.double("price")
        )
      }
    ) { products ->
      products.size shouldBeGreaterThan 0
      products.all { it.price > 500 } shouldBe true
    }
  }
}
```

Use `row.stringOrNull(...)` for nullable columns. Joins / aggregations / CTEs work identically. Just write the SQL.

### Direct operations

For advanced control (parameterized inserts, custom mappers):

```kotlin
stove {
  postgresql {
    val ops = operations()

    ops.execute(
      "INSERT INTO users (name, email) VALUES (?, ?)",
      Parameter("name", "Alice"),
      Parameter("email", "alice@example.com")
    )

    val users = ops.select("SELECT * FROM users") { row ->
      User(
        id = row.long("id"),
        name = row.string("name"),
        email = row.string("email")
      )
    }
  }
}
```

## Fault injection: pause / unpause

Container mode only. Simulate database outage mid-test:

```kotlin
stove {
  postgresql {
    pause()

    http {
      get("/health") { it.status shouldBe 503 }  // app reports degraded
    }

    unpause()
  }
}
```

## Complete example

```kotlin hl_lines="8 18 31"
test("create user via API, verify row + published event") {
  stove {
    val userName = "John Doe"
    val userEmail = "john@example.com"

    http {
      postAndExpectBody<UserResponse>(
        uri = "/users",
        body = CreateUserRequest(name = userName, email = userEmail).some()
      ) { response ->
        response.status shouldBe 201
        response.body().name shouldBe userName
      }
    }

    postgresql {
      shouldQuery<User>(
        query = "SELECT * FROM users WHERE email = ?",
        mapper = { row ->
          User(
            id = row.long("id"),
            name = row.string("name"),
            email = row.string("email")
          )
        }
      ) { users ->
        users.size shouldBe 1
        users.first().name shouldBe userName
      }
    }

    kafka {
      shouldBePublished<UserCreatedEvent> {
        actual.name == userName && actual.email == userEmail
      }
    }
  }
}
```

## Multiple databases on one container

Production may have separate Postgres instances per bounded context. In tests, one container with multiple `CREATE DATABASE` calls is enough. Your app reads them as separate datasources.

```kotlin
class CreateDatabasesMigration : DatabaseMigration<PostgresSqlMigrationContext> {
  override val order: Int = 0

  override suspend fun execute(connection: PostgresSqlMigrationContext) {
    listOf("users_db", "orders_db", "analytics_db").forEach { db ->
      connection.operations.execute("CREATE DATABASE IF NOT EXISTS $db")
    }
  }
}

Stove().with {
  postgresql {
    PostgresqlOptions(
      databaseName = "main",
      configureExposedConfiguration = { cfg ->
        val base = "jdbc:postgresql://${cfg.host}:${cfg.port}"
        listOf(
          "db.users.url=$base/users_db",
          "db.users.username=${cfg.username}",
          "db.users.password=${cfg.password}",
          "db.orders.url=$base/orders_db",
          "db.orders.username=${cfg.username}",
          "db.orders.password=${cfg.password}",
          "db.analytics.url=$base/analytics_db",
          "db.analytics.username=${cfg.username}",
          "db.analytics.password=${cfg.password}"
        )
      }
    ).migrations {
      register<CreateDatabasesMigration>()
    }
  }
}
```

For genuinely independent test instances (different versions, different config), use [keyed systems](20-multiple-systems.md): `postgresql(AppDb) { ... }` plus `postgresql(AnalyticsDb) { ... }`.

## Shared infrastructure

Use `PostgresqlOptions.provided(...)` to wire to existing Postgres. See [Provided Instances](11-provided-instances.md) for unique-prefix isolation patterns.

## Pairs well with

- [Bridge](10-bridge.md). Verify via the app's own repositories when needed
- [Tracing](15-tracing.md). JDBC spans appear in the trace tree on failure
- [Recipes · order flow](../recipes/order-flow.md). Full multi-system example
