# MySQL

=== "Gradle"

    ``` kotlin
        dependencies {
            testImplementation(platform("com.trendyol:stove-bom:$version"))
            testImplementation("com.trendyol:stove-mysql")
        }
    ```

## Configure

Once you've added the dependency, you can configure MySQL in your Stove setup:

```kotlin
Stove()
  .with {
    mysql {
      MySqlOptions {
        listOf(
          "mysql.jdbcUrl=${it.jdbcUrl}",
          "mysql.host=${it.host}",
          "mysql.port=${it.port}",
          "mysql.username=${it.username}",
          "mysql.password=${it.password}"
        )
      }
    }
  }.run()
```

The `it` reference gives you access to the MySQL container's connection details, which you can pass to your application.

## Migrations

Stove provides a way to run database migrations before tests start:

```kotlin
class InitialMigration : DatabaseMigration<MySqlMigrationContext> {
  override val order: Int = 1

  override suspend fun execute(connection: MySqlMigrationContext) {
    connection.operations.execute(
      """
      CREATE TABLE IF NOT EXISTS users (
        id INT AUTO_INCREMENT PRIMARY KEY,
        name VARCHAR(100) NOT NULL,
        email VARCHAR(100) NOT NULL UNIQUE,
        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
      );
      """.trimIndent()
    )
  }
}
```

Register migrations in your Stove configuration:

```kotlin
Stove()
  .with {
    mysql {
      MySqlOptions(
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
stove {
  mysql {
    // Create tables
    shouldExecute(
      """
      DROP TABLE IF EXISTS products;
      CREATE TABLE IF NOT EXISTS products (
        id INT AUTO_INCREMENT PRIMARY KEY,
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

stove {
  mysql {
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
stove {
  mysql {
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

stove {
  mysql {
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
