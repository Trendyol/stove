# MSSQL

Real Microsoft SQL Server in a container or wired to an existing instance. `shouldExecute(sql)`, `shouldQuery<T>(sql, mapper)`, raw `ops { }` access, pause/unpause.

<a class="open-in-wizard" data-sys="mssql">Open in setup wizard</a>

<!--{wizard:snippet id=sys.mssql parts=gradle,configure,test}-->

<div class="stove-tldr" markdown>
<span class="stove-tldr-title">In 30 seconds</span>
Register <code>mssql { MsSqlOptions(databaseName, userName, password) }</code>. T-SQL syntax. <code>shouldQuery&lt;T&gt;(sql) { row -&gt; T(...) }</code> with a row mapper lambda; <code>shouldExecute</code> for DDL/DML. Stored procs and identity columns work as normal.
</div>

## Configure

```kotlin
Stove().with {
  mssql {
    MsSqlOptions(
      databaseName = "testdb",
      userName = "sa",
      password = "Strong-Passw0rd!",
      applicationName = "stove-e2e",
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

## Migrations

```kotlin
class CreateOrdersTable : DatabaseMigration<MsSqlMigrationContext> {
  override val order = 1
  override suspend fun execute(ctx: MsSqlMigrationContext) {
    ctx.operations.execute(
      """
      CREATE TABLE orders (
        id INT IDENTITY(1,1) PRIMARY KEY,
        user_id NVARCHAR(50) NOT NULL,
        amount DECIMAL(10,2) NOT NULL,
        created_at DATETIME2 DEFAULT SYSUTCDATETIME()
      )
      """.trimIndent()
    )
  }
}

mssql {
  MsSqlOptions(/* ... */).migrations { register<CreateOrdersTable>() }
}
```

## DSL

```kotlin
data class Order(val id: Int, val userId: String, val amount: Double)

stove {
  mssql {
    shouldExecute(
      "INSERT INTO orders (user_id, amount) VALUES ('u1', 99.99)"
    )

    shouldQuery<Order>(
      query = "SELECT id, user_id, amount FROM orders WHERE user_id = 'u1'",
      mapper = { rs ->
        Order(
          id = rs.getInt("id"),
          userId = rs.getString("user_id"),
          amount = rs.getDouble("amount")
        )
      }
    ) { orders ->
      orders shouldHaveSize 1
    }
  }
}
```

### Raw operations + stored procs

```kotlin
stove {
  mssql {
    ops {
      execute("EXEC sp_recalculate_totals @userId = 'u1'")

      val rows = select("SELECT TOP 10 * FROM orders") { rs ->
        Order(rs.getInt("id"), rs.getString("user_id"), rs.getDouble("amount"))
      }
    }
  }
}
```

### Pause / unpause (container mode)

```kotlin
mssql { pause(); unpause() }
```

## Pitfalls

| Symptom | Fix |
|---|---|
| Container won't start | MSSQL needs ~2GB RAM; raise Docker memory limit |
| `Login failed for user sa` | Password must satisfy SQL Server complexity (uppercase + lower + digit + symbol) |
| Identity column unexpected gaps | Normal under rollback; don't assert exact IDs |

## Pairs well with

- [Provided Instances](11-provided-instances.md) for shared SQL Server (use schema isolation)
- [Best Practices · serde](../best-practices.md#serialization) when persisting JSON columns
