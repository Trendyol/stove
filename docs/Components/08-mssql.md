# <span data-rn="underline" data-rn-color="#ff9800">Microsoft SQL Server (MSSQL)</span>

=== "Gradle"

    ``` kotlin
        dependencies {
            testImplementation(platform("com.trendyol:stove-bom:$version"))
            testImplementation("com.trendyol:stove-mssql")
        }
    ```

## Configure

After getting the library from the maven source, while configuring TestSystem you will have access to `mssql` function.
This function configures the MSSQL Docker container that is going to be started.

```kotlin hl_lines="4 9-12"
Stove()
  .with {
    mssql {
      MsSqlOptions(
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

### Container Options

Customize the MSSQL container:

```kotlin
Stove()
  .with {
    mssql {
      MsSqlOptions(
        container = MsSqlContainerOptions(
          registry = "mcr.microsoft.com/",
          image = "mssql/server",
          tag = "2019-latest",
          containerFn = { container ->
            container.withEnv("ACCEPT_EULA", "Y")
          }
        ),
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

## Migrations

Stove provides a way to run database migrations before tests start:

```kotlin
class InitialMigration : DatabaseMigration<MsSqlMigrationContext> {
  override val order: Int = 1

  override suspend fun execute(connection: MsSqlMigrationContext) {
    connection.operations.execute(
      """
      CREATE TABLE Person (
        PersonID INT PRIMARY KEY IDENTITY(1,1),
        LastName VARCHAR(255) NOT NULL,
        FirstName VARCHAR(255) NOT NULL,
        Address VARCHAR(255),
        City VARCHAR(255)
      );
      """.trimIndent()
    )
  }
}

class CreateOrdersTableMigration : DatabaseMigration<MsSqlMigrationContext> {
  override val order: Int = 2

  override suspend fun execute(connection: MsSqlMigrationContext) {
    connection.operations.execute(
      """
      CREATE TABLE Orders (
        OrderID INT PRIMARY KEY IDENTITY(1,1),
        PersonID INT NOT NULL,
        OrderDate DATETIME DEFAULT GETDATE(),
        Amount DECIMAL(10, 2),
        FOREIGN KEY (PersonID) REFERENCES Person(PersonID)
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
    mssql {
      MsSqlOptions(
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
      ).migrations {
        register<InitialMigration>()
        register<CreateOrdersTableMigration>()
      }
    }
  }
  .run()
```

## Usage

### Executing SQL

Execute DDL and DML statements:

```kotlin hl_lines="11 16 24 27"
stove {
  mssql {
    // Create tables
    shouldExecute(
      """
      CREATE TABLE Products (
        ProductID INT PRIMARY KEY IDENTITY(1,1),
        ProductName NVARCHAR(100) NOT NULL,
        Price DECIMAL(10, 2) NOT NULL,
        Stock INT DEFAULT 0,
        CreatedAt DATETIME DEFAULT GETDATE()
      );
      """.trimIndent()
    )

    // Insert data
    shouldExecute(
      """
      INSERT INTO Products (ProductName, Price, Stock) 
      VALUES ('Laptop', 999.99, 10)
      """.trimIndent()
    )

    // Update data
    shouldExecute("UPDATE Products SET Stock = 5 WHERE ProductName = 'Laptop'")

    // Delete data
    shouldExecute("DELETE FROM Products WHERE Stock = 0")
  }
}
```

### Querying Data

Query data with type-safe mappers:

```kotlin hl_lines="14 17 28-29"
data class Person(
  val personId: Int,
  val lastName: String,
  val firstName: String,
  val address: String?,
  val city: String?
)

stove {
  mssql {
    // Insert test data
    shouldExecute("INSERT INTO Person (LastName, FirstName, Address, City) VALUES ('Doe', 'John', '123 Main St', 'Springfield')")

    // Query with mapper
    shouldQuery<Person>(
      query = "SELECT * FROM Person",
      mapper = { resultSet ->
        Person(
          personId = resultSet.getInt(1),
          lastName = resultSet.getString(2),
          firstName = resultSet.getString(3),
          address = resultSet.getString(4),
          city = resultSet.getString(5)
        )
      }
    ) { result ->
      result.size shouldBe 1
      result.first().apply {
        personId shouldBe 1
        lastName shouldBe "Doe"
        firstName shouldBe "John"
        address shouldBe "123 Main St"
        city shouldBe "Springfield"
      }
    }
  }
}
```

### Using Operations Directly

Access SQL operations directly for advanced use cases:

```kotlin
stove {
  mssql {
    ops {
      // Simple select
      val result = select("SELECT 1 AS value") {
        it.getInt(1)
      }
      result.first() shouldBe 1

      // Execute insert
      execute("INSERT INTO Person (LastName, FirstName) VALUES ('Smith', 'Jane')")

      // Select with parameters
      val users = select("SELECT * FROM Person WHERE LastName = 'Smith'") { rs ->
        Person(
          personId = rs.getInt("PersonID"),
          lastName = rs.getString("LastName"),
          firstName = rs.getString("FirstName"),
          address = rs.getString("Address"),
          city = rs.getString("City")
        )
      }
      users.size shouldBeGreaterThan 0
    }
  }
}
```

### Complex Queries

Execute joins, aggregations, and complex queries:

```kotlin
data class OrderSummary(
  val personId: Int,
  val personName: String,
  val totalOrders: Int,
  val totalAmount: Double
)

stove {
  mssql {
    // Setup test data
    shouldExecute("INSERT INTO Person (LastName, FirstName, Address, City) VALUES ('Doe', 'John', '123 Main St', 'NYC')")
    shouldExecute("INSERT INTO Orders (PersonID, Amount) VALUES (1, 100.00)")
    shouldExecute("INSERT INTO Orders (PersonID, Amount) VALUES (1, 250.50)")
    shouldExecute("INSERT INTO Orders (PersonID, Amount) VALUES (1, 75.25)")

    // Aggregate query
    shouldQuery<OrderSummary>(
      query = """
        SELECT 
          p.PersonID,
          CONCAT(p.FirstName, ' ', p.LastName) AS PersonName,
          COUNT(o.OrderID) AS TotalOrders,
          SUM(o.Amount) AS TotalAmount
        FROM Person p
        INNER JOIN Orders o ON p.PersonID = o.PersonID
        GROUP BY p.PersonID, p.FirstName, p.LastName
        HAVING COUNT(o.OrderID) > 0
      """.trimIndent(),
      mapper = { rs ->
        OrderSummary(
          personId = rs.getInt("PersonID"),
          personName = rs.getString("PersonName"),
          totalOrders = rs.getInt("TotalOrders"),
          totalAmount = rs.getDouble("TotalAmount")
        )
      }
    ) { summaries ->
      summaries.size shouldBe 1
      summaries.first().apply {
        personName shouldBe "John Doe"
        totalOrders shouldBe 3
        totalAmount shouldBe 425.75
      }
    }
  }
}
```

### Working with Nullable Fields

Handle nullable columns properly:

```kotlin
data class PersonWithNullable(
  val personId: Int,
  val firstName: String,
  val lastName: String,
  val address: String?,
  val city: String?,
  val email: String?
)

stove {
  mssql {
    // Insert with null values
    shouldExecute("INSERT INTO Person (LastName, FirstName) VALUES ('Solo', 'Han')")

    shouldQuery<PersonWithNullable>(
      query = "SELECT * FROM Person WHERE LastName = 'Solo'",
      mapper = { rs ->
        PersonWithNullable(
          personId = rs.getInt("PersonID"),
          firstName = rs.getString("FirstName"),
          lastName = rs.getString("LastName"),
          address = rs.getString("Address"), // Can be null
          city = rs.getString("City"), // Can be null
          email = rs.getString("Email") // Can be null
        )
      }
    ) { persons ->
      persons.first().apply {
        firstName shouldBe "Han"
        lastName shouldBe "Solo"
        address shouldBe null
        city shouldBe null
      }
    }
  }
}
```

### Pause and Unpause Container

Test failure scenarios:

```kotlin
stove {
  mssql {
    // Database is running
    shouldQuery<Int>(
      "SELECT COUNT(*) FROM Person",
      mapper = { rs -> rs.getInt(1) }
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
    shouldQuery<Int>(
      "SELECT COUNT(*) FROM Person",
      mapper = { rs -> rs.getInt(1) }
    ) { result ->
      result.first() shouldBeGreaterThanOrEqual 0
    }
  }
}
```

## Complete Example

Here's a <span data-rn="underline" data-rn-color="#009688">complete end-to-end test</span>:

```kotlin hl_lines="15 25"
data class User(
  val id: Int,
  val username: String,
  val email: String,
  val createdAt: LocalDateTime
)

test("should create user via API and verify in database") {
  stove {
    val username = "johndoe"
    val email = "john@example.com"

    // Create user via API
    http {
      postAndExpectBody<UserResponse>(
        uri = "/users",
        body = CreateUserRequest(username = username, email = email).some()
      ) { response ->
        response.status shouldBe 201
        response.body().username shouldBe username
      }
    }

    // Verify in MSSQL
    mssql {
      shouldQuery<User>(
        query = "SELECT * FROM Users WHERE Email = '$email'",
        mapper = { rs ->
          User(
            id = rs.getInt("UserID"),
            username = rs.getString("Username"),
            email = rs.getString("Email"),
            createdAt = rs.getTimestamp("CreatedAt").toLocalDateTime()
          )
        }
      ) { users ->
        users.size shouldBe 1
        users.first().apply {
          username shouldBe "johndoe"
          email shouldBe "john@example.com"
        }
      }
    }

    // Verify event was published
    kafka {
      shouldBePublished<UserCreatedEvent>(atLeastIn = 10.seconds) {
        actual.username == username &&
        actual.email == email
      }
    }
  }
}
```

## Integration with Application

Use the bridge to access application components:

```kotlin
test("should use repository to save user") {
  stove {
    val user = User(id = 0, username = "janedoe", email = "jane@example.com", createdAt = LocalDateTime.now())

    // Use application's repository
    using<UserRepository> {
      save(user)
    }

    // Verify in database
    mssql {
      shouldQuery<User>(
        query = "SELECT * FROM Users WHERE Username = 'janedoe'",
        mapper = { rs ->
          User(
            id = rs.getInt("UserID"),
            username = rs.getString("Username"),
            email = rs.getString("Email"),
            createdAt = rs.getTimestamp("CreatedAt").toLocalDateTime()
          )
        }
      ) { users ->
        users.size shouldBe 1
        users.first().email shouldBe "jane@example.com"
      }
    }
  }
}
```

## Batch Operations

Execute multiple operations:

```kotlin
stove {
  mssql {
    // Create tables
    shouldExecute(
      """
      CREATE TABLE Categories (
        CategoryID INT PRIMARY KEY IDENTITY(1,1),
        CategoryName NVARCHAR(50) NOT NULL
      );
      CREATE TABLE Products (
        ProductID INT PRIMARY KEY IDENTITY(1,1),
        ProductName NVARCHAR(100) NOT NULL,
        CategoryID INT REFERENCES Categories(CategoryID)
      );
      """.trimIndent()
    )

    // Insert categories
    listOf("Electronics", "Books", "Clothing").forEach { category ->
      shouldExecute("INSERT INTO Categories (CategoryName) VALUES ('$category')")
    }

    // Verify all inserted
    shouldQuery<String>(
      "SELECT CategoryName FROM Categories",
      mapper = { it.getString("CategoryName") }
    ) { categories ->
      categories.size shouldBe 3
      categories shouldContainAll listOf("Electronics", "Books", "Clothing")
    }
  }
}
```

## Stored Procedures

Test stored procedures:

```kotlin
stove {
  mssql {
    // Create stored procedure
    shouldExecute(
      """
      CREATE PROCEDURE GetPersonsByCity
        @City NVARCHAR(100)
      AS
      BEGIN
        SELECT * FROM Person WHERE City = @City
      END
      """.trimIndent()
    )

    // Insert test data
    shouldExecute("INSERT INTO Person (LastName, FirstName, City) VALUES ('Doe', 'John', 'NYC')")
    shouldExecute("INSERT INTO Person (LastName, FirstName, City) VALUES ('Smith', 'Jane', 'NYC')")
    shouldExecute("INSERT INTO Person (LastName, FirstName, City) VALUES ('Brown', 'Bob', 'LA')")

    // Execute stored procedure
    shouldQuery<Person>(
      query = "EXEC GetPersonsByCity @City = 'NYC'",
      mapper = { rs ->
        Person(
          personId = rs.getInt("PersonID"),
          lastName = rs.getString("LastName"),
          firstName = rs.getString("FirstName"),
          address = rs.getString("Address"),
          city = rs.getString("City")
        )
      }
    ) { persons ->
      persons.size shouldBe 2
      persons.all { it.city == "NYC" } shouldBe true
    }
  }
}
```

## Transactions

Test transaction behavior:

```kotlin
stove {
  mssql {
    ops {
      // Start transaction manually via SQL
      execute("BEGIN TRANSACTION")
      
      try {
        execute("INSERT INTO Person (LastName, FirstName) VALUES ('Test', 'User1')")
        execute("INSERT INTO Person (LastName, FirstName) VALUES ('Test', 'User2')")
        
        // Commit transaction
        execute("COMMIT TRANSACTION")
      } catch (e: Exception) {
        execute("ROLLBACK TRANSACTION")
        throw e
      }

      // Verify
      val count = select("SELECT COUNT(*) FROM Person WHERE LastName = 'Test'") { it.getInt(1) }
      count.first() shouldBe 2
    }
  }
}
```

## Provided Instance (External MSSQL)

For CI/CD pipelines or shared infrastructure:

```kotlin
Stove()
  .with {
    mssql {
      MsSqlOptions.provided(
        jdbcUrl = System.getenv("MSSQL_JDBC_URL") ?: "jdbc:sqlserver://localhost:1433;databaseName=testdb",
        host = System.getenv("MSSQL_HOST") ?: "localhost",
        port = System.getenv("MSSQL_PORT")?.toInt() ?: 1433,
        databaseName = "testdb",
        username = System.getenv("MSSQL_USERNAME") ?: "sa",
        password = System.getenv("MSSQL_PASSWORD") ?: "YourStrong@Passw0rd",
        runMigrations = true,
        cleanup = { operations ->
          operations.execute("DELETE FROM Orders")
          operations.execute("DELETE FROM Person")
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

## Data Types

Working with various SQL Server data types:

```kotlin
data class DataTypesExample(
  val id: Int,
  val intValue: Int,
  val bigIntValue: Long,
  val decimalValue: BigDecimal,
  val floatValue: Double,
  val bitValue: Boolean,
  val dateValue: LocalDate,
  val timeValue: LocalTime,
  val dateTimeValue: LocalDateTime,
  val nvarcharValue: String,
  val varcharValue: String
)

stove {
  mssql {
    // Create table with various types
    shouldExecute(
      """
      CREATE TABLE DataTypes (
        ID INT PRIMARY KEY IDENTITY(1,1),
        IntValue INT,
        BigIntValue BIGINT,
        DecimalValue DECIMAL(18, 4),
        FloatValue FLOAT,
        BitValue BIT,
        DateValue DATE,
        TimeValue TIME,
        DateTimeValue DATETIME2,
        NVarcharValue NVARCHAR(100),
        VarcharValue VARCHAR(100)
      )
      """.trimIndent()
    )

    // Insert test data
    shouldExecute(
      """
      INSERT INTO DataTypes 
        (IntValue, BigIntValue, DecimalValue, FloatValue, BitValue, DateValue, TimeValue, DateTimeValue, NVarcharValue, VarcharValue)
      VALUES 
        (42, 9223372036854775807, 1234.5678, 3.14159, 1, '2024-01-15', '14:30:00', '2024-01-15 14:30:00', N'Unicode: 日本語', 'ASCII text')
      """.trimIndent()
    )

    // Query and verify
    shouldQuery<DataTypesExample>(
      query = "SELECT * FROM DataTypes",
      mapper = { rs ->
        DataTypesExample(
          id = rs.getInt("ID"),
          intValue = rs.getInt("IntValue"),
          bigIntValue = rs.getLong("BigIntValue"),
          decimalValue = rs.getBigDecimal("DecimalValue"),
          floatValue = rs.getDouble("FloatValue"),
          bitValue = rs.getBoolean("BitValue"),
          dateValue = rs.getDate("DateValue").toLocalDate(),
          timeValue = rs.getTime("TimeValue").toLocalTime(),
          dateTimeValue = rs.getTimestamp("DateTimeValue").toLocalDateTime(),
          nvarcharValue = rs.getString("NVarcharValue"),
          varcharValue = rs.getString("VarcharValue")
        )
      }
    ) { results ->
      results.first().apply {
        intValue shouldBe 42
        bitValue shouldBe true
        nvarcharValue shouldContain "日本語"
      }
    }
  }
}
```
