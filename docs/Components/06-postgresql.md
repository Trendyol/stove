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

## Usage

```kotlin
TestSystem.validate {
  postgresql {
    shouldExecute(
      """
              DROP TABLE IF EXISTS Dummies;                    
              CREATE TABLE IF NOT EXISTS Dummies (
              	id serial PRIMARY KEY,
              	description VARCHAR (50)  NOT NULL
              );
      """.trimIndent()
    )
    shouldExecute("INSERT INTO Dummies (description) VALUES ('${testCase.name.testName}')")
    shouldQuery<IdAndDescription>("SELECT * FROM Dummies", mapper = {
      IdAndDescription(it.getLong("id"), it.getString("description"))
    }) { actual ->
      actual.size shouldBeGreaterThan 0
      actual.first().description shouldBe testCase.name.testName
    }
  }
}
```
