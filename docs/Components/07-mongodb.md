# Mongodb

=== "Gradle"

    ``` kotlin
        dependencies {
            testImplementation("com.trendyol:stove-testing-e2e-mongodb:$version")
        }
    ```

## Configure

```kotlin
TestSystem()
  .with {
    mongodb {
      MongodbSystemOptions(
        listOf(
          "mongodb.host=${it.host}",
          "mongodb.port=${it.port}",
          "mongodb.database=${it.database}",
          "mongodb.username=${it.username}",
          "mongodb.password=${it.password}"
        )
      )
    }
  }
  .run()
```

## Usage

```kotlin
test("should save and get with string objectId") {
  val id = ObjectId()
  validate {
    mongodb {
      save(
        ExampleInstanceWithStringObjectId(
          id = id.toHexString(),
          aggregateId = id.toHexString(),
          description = testCase.name.testName
        ),
        id.toHexString()
      )
      shouldGet<ExampleInstanceWithStringObjectId>(id.toHexString()) { actual ->
        actual.aggregateId shouldBe id.toHexString()
        actual.description shouldBe testCase.name.testName
      }
    }
  }
}
```

