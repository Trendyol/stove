# Postgresql

=== "Gradle"

    ``` kotlin
        dependencies {
            testImplementation("com.trendyol:stove-testing-e2e-rdbms-postgres:$version")
        }
    ```

## Configure

```kotlin
TestSystem()
  .with {
    postgresql {
      PostgresqlSystemOptions {
        listOf(
          "postgresql.host=${it.host}",
          "postgresql.port=${it.port}",
          "postgresql.database=${it.database}",
          "postgresql.username=${it.username}",
          "postgresql.password=${it.password}"
        )
      }
    }
  }.run()
```

## Migrations

Stove provides a way to run database migrations before tests start:

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

Register migrations in your TestSystem configuration:

```kotlin
TestSystem()
  .with {
    postgresql {
      PostgresqlOptions(
        databaseName = "testing",
        configureExposedConfiguration = { cfg ->
          listOf(
            "spring.datasource.url=${cfg.jdbcUrl}",
            "spring.datasource.username=${cfg.username}",
            "spring.datasource.password=${cfg.password}"
          )
        }
      ).migrations {
        register<InitialMigration>()
      }
    }
  }
  .run()
```

## Usage

### Executing SQL

Execute DDL and DML statements:

```kotlin
TestSystem.validate {
  postgresql {
    // Create tables
    shouldExecute(
      """
      DROP TABLE IF EXISTS products;
      CREATE TABLE IF NOT EXISTS products (
        id serial PRIMARY KEY,
        name VARCHAR(100) NOT NULL,
        price DECIMAL(10, 2) NOT NULL,
        stock INT DEFAULT 0
      );
      """.trimIndent()
    )

    // Insert data
    shouldExecute(
      """
      INSERT INTO products (name, price, stock) 
      VALUES ('Laptop', 999.99, 10)
      """.trimIndent()
    )

    // Update data
    shouldExecute("UPDATE products SET stock = 5 WHERE name = 'Laptop'")

    // Delete data
    shouldExecute("DELETE FROM products WHERE stock = 0")
  }
}
```

### Querying Data

Query data with type-safe mappers:

```kotlin
data class Product(
  val id: Long,
  val name: String,
  val price: Double,
  val stock: Int
)

TestSystem.validate {
  postgresql {
    shouldQuery<Product>(
      query = "SELECT * FROM products WHERE price > 500",
      mapper = { row ->
        Product(
          id = row.long("id"),
          name = row.string("name"),
          price = row.double("price"),
          stock = row.int("stock")
        )
      }
    ) { products ->
      products.size shouldBeGreaterThan 0
      products.all { it.price > 500 } shouldBe true
    }
  }
}
```

### Query with Parameters

Use parameterized queries for safety:

```kotlin
TestSystem.validate {
  postgresql {
    val minPrice = 100.0
    shouldQuery<Product>(
      query = "SELECT * FROM products WHERE price >= ?",
      mapper = { row ->
        Product(
          id = row.long("id"),
          name = row.string("name"),
          price = row.double("price"),
          stock = row.int("stock")
        )
      }
    ) { products ->
      products.all { it.price >= minPrice } shouldBe true
    }
  }
}
```

### Working with Nullable Fields

Handle nullable columns:

```kotlin
data class User(
  val id: Long,
  val name: String,
  val email: String?,
  val phone: String?
)

TestSystem.validate {
  postgresql {
    shouldQuery<User>(
      query = "SELECT * FROM users",
      mapper = { row ->
        User(
          id = row.long("id"),
          name = row.string("name"),
          email = row.stringOrNull("email"),
          phone = row.stringOrNull("phone")
        )
      }
    ) { users ->
      users.size shouldBeGreaterThan 0
    }
  }
}
```

### Complex Queries

Execute joins and aggregations:

```kotlin
data class OrderSummary(
  val userId: Long,
  val userName: String,
  val totalOrders: Int,
  val totalAmount: Double
)

TestSystem.validate {
  postgresql {
    shouldQuery<OrderSummary>(
      query = """
        SELECT 
          u.id as user_id,
          u.name as user_name,
          COUNT(o.id) as total_orders,
          SUM(o.amount) as total_amount
        FROM users u
        LEFT JOIN orders o ON u.id = o.user_id
        GROUP BY u.id, u.name
        HAVING COUNT(o.id) > 0
      """.trimIndent(),
      mapper = { row ->
        OrderSummary(
          userId = row.long("user_id"),
          userName = row.string("user_name"),
          totalOrders = row.int("total_orders"),
          totalAmount = row.double("total_amount")
        )
      }
    ) { summaries ->
      summaries.all { it.totalOrders > 0 } shouldBe true
    }
  }
}
```

### Pause and Unpause Container

Test failure scenarios:

```kotlin
TestSystem.validate {
  postgresql {
    // Database is running
    shouldQuery<Product>(
      "SELECT COUNT(*) as count FROM products",
      mapper = { row -> row.int("count") }
    ) { result ->
      result.first() shouldBeGreaterThanOrEqual 0
    }

    // Pause the database
    pause()

    // Your application should handle the failure
    // ...

    // Unpause the database
    unpause()

    // Verify recovery
    shouldQuery<Product>(
      "SELECT COUNT(*) as count FROM products",
      mapper = { row -> row.int("count") }
    ) { result ->
      result.first() shouldBeGreaterThanOrEqual 0
    }
  }
}
```

## Complete Example

Here's a complete end-to-end test:

```kotlin
test("should create user via API and verify in database") {
  TestSystem.validate {
    val userName = "John Doe"
    val userEmail = "john@example.com"

    // Create user via API
    http {
      postAndExpectBody<UserResponse>(
        uri = "/users",
        body = CreateUserRequest(name = userName, email = userEmail).some()
      ) { response ->
        response.status shouldBe 201
        response.body().name shouldBe userName
      }
    }

    // Verify in PostgreSQL
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
        users.first().email shouldBe userEmail
      }
    }

    // Verify event was published
    kafka {
      shouldBePublished<UserCreatedEvent>(atLeastIn = 10.seconds) {
        actual.name == userName &&
        actual.email == userEmail
      }
    }
  }
}
```

## Integration with Application

Use the bridge to access application components:

```kotlin
test("should use repository to save user") {
  TestSystem.validate {
    val user = User(id = 1L, name = "Jane Doe", email = "jane@example.com")

    // Use application's repository
    using<UserRepository> {
      save(user)
    }

    // Verify in database
    postgresql {
      shouldQuery<User>(
        query = "SELECT * FROM users WHERE id = ?",
        mapper = { row ->
          User(
            id = row.long("id"),
            name = row.string("name"),
            email = row.string("email")
          )
        }
      ) { users ->
        users.size shouldBe 1
        users.first().name shouldBe "Jane Doe"
      }
    }
  }
}
```

## Batch Operations

Execute multiple operations:

```kotlin
TestSystem.validate {
  postgresql {
    // Create tables
    shouldExecute(
      """
      CREATE TABLE IF NOT EXISTS categories (
        id serial PRIMARY KEY,
        name VARCHAR(50) NOT NULL
      );
      CREATE TABLE IF NOT EXISTS products (
        id serial PRIMARY KEY,
        name VARCHAR(100) NOT NULL,
        category_id INT REFERENCES categories(id)
      );
      """.trimIndent()
    )

    // Insert categories
    listOf("Electronics", "Books", "Clothing").forEach { category ->
      shouldExecute("INSERT INTO categories (name) VALUES ('$category')")
    }

    // Verify all inserted
    shouldQuery<String>(
      "SELECT name FROM categories",
      mapper = { it.string("name") }
    ) { categories ->
      categories.size shouldBe 3
      categories shouldContain "Electronics"
      categories shouldContain "Books"
    }
  }
}
```

## Advanced: Direct SQL Operations

Access SQL operations directly for advanced use cases:

```kotlin
TestSystem.validate {
  postgresql {
    val ops = operations()
    
    // Execute with parameters
    ops.execute(
      "INSERT INTO users (name, email) VALUES (?, ?)",
      Parameter("name", "Alice"),
      Parameter("email", "alice@example.com")
    )

    // Custom select operation
    val users = ops.select("SELECT * FROM users") { row ->
      User(
        id = row.long("id"),
        name = row.string("name"),
        email = row.string("email")
      )
    }

    users.size shouldBeGreaterThan 0
  }
}
```
