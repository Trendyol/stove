# MySQL

Real MySQL in a container or wired to existing infra. Same DSL shape as PostgreSQL and MSSQL: `shouldExecute(sql)`, `shouldQuery<T>(sql, mapper)`, migrations, raw ops.

<a class="open-in-wizard" data-sys="mysql">Open in setup wizard</a>

<!--{wizard:snippet id=sys.mysql parts=gradle,configure,test}-->

<div class="stove-tldr" markdown>
<span class="stove-tldr-title">In 30 seconds</span>
Register <code>mysql { MySqlOptions(databaseName, ...) }</code>. Parameterized queries with <code>?</code>. Row mapper returns your domain type. AUTO_INCREMENT IDs work as expected.
</div>

## Configure

```kotlin
Stove().with {
  mysql {
    MySqlOptions(
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

## Migrations

```kotlin
class CreateUsersTable : DatabaseMigration<MySqlMigrationContext> {
  override val order = 1
  override suspend fun execute(ctx: MySqlMigrationContext) {
    ctx.operations.execute(
      """
      CREATE TABLE IF NOT EXISTS users (
        id BIGINT AUTO_INCREMENT PRIMARY KEY,
        name VARCHAR(100) NOT NULL,
        email VARCHAR(100) NOT NULL UNIQUE,
        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
      )
      """.trimIndent()
    )
  }
}

mysql {
  MySqlOptions(/* ... */).migrations { register<CreateUsersTable>() }
}
```

## DSL

```kotlin
data class User(val id: Long, val name: String, val email: String?)

stove {
  mysql {
    shouldExecute("INSERT INTO users (name, email) VALUES ('Alice', 'a@x.com')")

    shouldQuery<User>(
      query = "SELECT * FROM users WHERE email = ?",
      mapper = { row ->
        User(
          id = row.long("id"),
          name = row.string("name"),
          email = row.stringOrNull("email")
        )
      }
    ) { users ->
      users shouldHaveSize 1
    }
  }
}
```

## Provided MySQL

```kotlin
mysql {
  MySqlOptions.provided(
    jdbcUrl = "jdbc:mysql://shared-mysql:3306/test",
    username = "test",
    password = "test",
    configureExposedConfiguration = { cfg -> listOf(/* ... */) }
  )
}
```

## Pitfalls

| Symptom | Fix |
|---|---|
| `Public Key Retrieval is not allowed` | Append `?allowPublicKeyRetrieval=true&useSSL=false` to the JDBC URL |
| Charset issues | Use `utf8mb4` collation in `CREATE TABLE` for full Unicode |
| AUTO_INCREMENT mismatch across runs | Don't assert exact IDs; verify by business key |

## Pairs well with

- [Provided Instances](11-provided-instances.md) for shared MySQL
- [Best Practices](../best-practices.md) for shared infra isolation
