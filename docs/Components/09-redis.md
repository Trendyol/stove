# Redis

=== "Gradle"

    ``` kotlin
        dependencies {
            testImplementation("com.trendyol:stove-testing-e2e-redis:$version")
        }
    ```

## Configure

```kotlin
TestSystem()
  .with {
    redis {
      RedisSystemOptions {
        listOf(
          "redis.host=${it.host}",
          "redis.port=${it.port}",
          "redis.password=${it.password}"
        )
      }
    }
  }.run()
```

## Usage

There is not much to do with Redis. You can use the `RedisSystem` object to interact with the Redis instance.

There is an extension function called `client` that gives the access to the underlying redis client of `RedisSystem`.

You can create any extension on top of `client` to interact with Redis.

```kotlin
class RedisSystem {
  // Reference to the Redis client, this is already located in RedisSystem.
  companion object {
    fun RedisSystem.client(): RedisClient {
      if (!isInitialized()) throw SystemNotInitializedException(RedisSystem::class)
      return client
    }
  }
}
```

Your tests:

```kotlin
validate {
  redis {
    client().use { client ->
      client.set("key", "value")
      val value = client.get("key")
      value shouldBe "value"
    }
  }
}
```


