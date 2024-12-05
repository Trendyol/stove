# Postgresql

=== "Gradle"

    ``` kotlin
        dependencies {
            testImplementation("com.trendyol:stove-testing-e2e-rdbms-mssql:$version")
        }
    ```

## Configure

```kotlin
TestSystem()
  .with {
    mssql {
      MssqlSystemOptions {
        listOf(
          "mssql.host=${it.host}",
          "mssql.port=${it.port}",
          "mssql.database=${it.database}",
          "mssql.username=${it.username}",
          "mssql.password=${it.password}"
        )
      }
    }
  }.run()
```

## Usage

```kotlin
validate {
  mssql {
    ops {
      val result = select("SELECT 1") {
        it.getInt(1)
      }
      result.first() shouldBe 1
    }
    shouldExecute("insert into Person values (1, 'Doe', 'John', '123 Main St', 'Springfield')")
    shouldQuery<Person>(
      query = "select * from Person",
      mapper = {
        Person(
          it.getInt(1),
          it.getString(2),
          it.getString(3),
          it.getString(4),
          it.getString(5)
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

