# <span data-rn="underline" data-rn-color="#ff9800">Redis</span>

=== "Gradle"

    ``` kotlin
        dependencies {
            testImplementation("com.trendyol:stove-redis:$version")
        }
    ```

## Configure

```kotlin hl_lines="4 6-9"
Stove()
  .with {
    redis {
      RedisOptions(
        configureExposedConfiguration = { cfg ->
          listOf(
            "redis.host=${cfg.host}",
            "redis.port=${cfg.port}",
            "redis.password=${cfg.password}"
          )
        }
      )
    }
  }.run()
```

## Migrations

Redis supports migrations for setting up initial data or configuration:

```kotlin
class SeedCacheData : DatabaseMigration<RedisMigrationContext> {
  override val order: Int = 1

  override suspend fun execute(connection: RedisMigrationContext) {
    connection.connection.sync().apply {
      // Seed initial cache data
      set("config:feature-flag", "enabled")
      hset("defaults:settings", mapOf(
        "timeout" to "30",
        "retries" to "3"
      ))
    }
  }
}

// Register migrations
redis {
  RedisOptions(
    configureExposedConfiguration = { cfg -> listOf(...) }
  ).migrations {
    register<SeedCacheData>()
  }
}
```

## Usage

The Redis component provides access to the underlying Lettuce Redis client, allowing you to <span data-rn="underline" data-rn-color="#009688">test all Redis operations</span>.

### Accessing the Redis Client

Access the Redis client using the `client()` extension function:

```kotlin
stove {
  redis {
    val redisClient = client()
    val connection = redisClient.connect()
    // Use the connection for Redis operations
    connection.close()
  }
}
```

### String Operations

Test basic string operations:

```kotlin hl_lines="3 7 8 12"
stove {
  redis {
    val connection = client().connect().sync()
    
    // SET and GET
    connection.set("user:123:name", "John Doe")
    val name = connection.get("user:123:name")
    name shouldBe "John Doe"
    
    // SET with expiration
    connection.setex("session:abc", 3600, "session-data")
    val ttl = connection.ttl("session:abc")
    ttl shouldBeGreaterThan 0
    
    // INCREMENT
    connection.set("counter", "0")
    connection.incr("counter")
    connection.incr("counter")
    val counter = connection.get("counter")
    counter shouldBe "2"
    
    // Multiple keys
    connection.mset(mapOf(
      "key1" to "value1",
      "key2" to "value2",
      "key3" to "value3"
    ))
    val values = connection.mget("key1", "key2", "key3")
    values.size shouldBe 3
  }
}
```

### Hash Operations

Test Redis hash operations:

```kotlin
stove {
  redis {
    val connection = client().connect().sync()
    
    // HSET and HGET
    connection.hset("user:123", "name", "John Doe")
    connection.hset("user:123", "email", "john@example.com")
    connection.hset("user:123", "age", "30")
    
    val name = connection.hget("user:123", "name")
    name shouldBe "John Doe"
    
    // HGETALL
    val user = connection.hgetall("user:123")
    user["name"] shouldBe "John Doe"
    user["email"] shouldBe "john@example.com"
    user["age"] shouldBe "30"
    
    // HMSET
    connection.hmset("product:456", mapOf(
      "name" to "Laptop",
      "price" to "999.99",
      "stock" to "10"
    ))
    
    // HINCRBY
    connection.hincrby("product:456", "stock", -1)
    val stock = connection.hget("product:456", "stock")
    stock shouldBe "9"
    
    // HDEL
    connection.hdel("user:123", "age")
    val age = connection.hget("user:123", "age")
    age shouldBe null
  }
}
```

### List Operations

Test Redis list operations:

```kotlin
stove {
  redis {
    val connection = client().connect().sync()
    
    // LPUSH and RPUSH
    connection.rpush("queue:tasks", "task1", "task2", "task3")
    connection.lpush("queue:tasks", "urgent-task")
    
    // LRANGE
    val tasks = connection.lrange("queue:tasks", 0, -1)
    tasks.size shouldBe 4
    tasks.first() shouldBe "urgent-task"
    
    // LPOP and RPOP
    val firstTask = connection.lpop("queue:tasks")
    firstTask shouldBe "urgent-task"
    
    val lastTask = connection.rpop("queue:tasks")
    lastTask shouldBe "task3"
    
    // LLEN
    val length = connection.llen("queue:tasks")
    length shouldBe 2
  }
}
```

### Set Operations

Test Redis set operations:

```kotlin
stove {
  redis {
    val connection = client().connect().sync()
    
    // SADD
    connection.sadd("tags:123", "kotlin", "testing", "redis")
    
    // SMEMBERS
    val tags = connection.smembers("tags:123")
    tags.size shouldBe 3
    tags shouldContain "kotlin"
    
    // SISMEMBER
    val isKotlin = connection.sismember("tags:123", "kotlin")
    isKotlin shouldBe true
    
    // SREM
    connection.srem("tags:123", "redis")
    val remainingTags = connection.smembers("tags:123")
    remainingTags.size shouldBe 2
    
    // Set operations
    connection.sadd("set1", "a", "b", "c")
    connection.sadd("set2", "b", "c", "d")
    
    // SINTER (intersection)
    val intersection = connection.sinter("set1", "set2")
    intersection.size shouldBe 2
    intersection shouldContain "b"
    intersection shouldContain "c"
    
    // SUNION
    val union = connection.sunion("set1", "set2")
    union.size shouldBe 4
  }
}
```

### Sorted Set Operations

Test Redis sorted set operations:

```kotlin
stove {
  redis {
    val connection = client().connect().sync()
    
    // ZADD
    connection.zadd("leaderboard", 100.0, "player1")
    connection.zadd("leaderboard", 250.0, "player2")
    connection.zadd("leaderboard", 175.0, "player3")
    
    // ZRANGE (ascending)
    val ascending = connection.zrange("leaderboard", 0, -1)
    ascending.size shouldBe 3
    ascending.first() shouldBe "player1"
    ascending.last() shouldBe "player2"
    
    // ZREVRANGE (descending)
    val descending = connection.zrevrange("leaderboard", 0, -1)
    descending.first() shouldBe "player2"
    
    // ZSCORE
    val score = connection.zscore("leaderboard", "player2")
    score shouldBe 250.0
    
    // ZRANK
    val rank = connection.zrank("leaderboard", "player3")
    rank shouldBe 1L // 0-indexed
    
    // ZINCRBY
    connection.zincrby("leaderboard", 50.0, "player1")
    val newScore = connection.zscore("leaderboard", "player1")
    newScore shouldBe 150.0
  }
}
```

### Async Operations

Use async operations for better performance:

```kotlin
stove {
  redis {
    val connection = client().connect().async()
    
    // Async SET
    val setFuture = connection.set("async:key", "async:value")
    setFuture.await() shouldBe "OK"
    
    // Async GET
    val getFuture = connection.get("async:key")
    val value = getFuture.await()
    value shouldBe "async:value"
    
    // Pipeline multiple operations
    connection.setAutoFlushCommands(false)
    val futures = listOf(
      connection.set("key1", "value1"),
      connection.set("key2", "value2"),
      connection.set("key3", "value3")
    )
    connection.flushCommands()
    
    futures.forEach { it.await() shouldBe "OK" }
  }
}
```

### Pub/Sub Operations

Test Redis Pub/Sub:

```kotlin
stove {
  redis {
    val pubConnection = client().connectPubSub().sync()
    val subConnection = client().connectPubSub().sync()
    
    // Subscribe to channel
    val messages = mutableListOf<String>()
    subConnection.addListener(object : RedisPubSubAdapter<String, String>() {
      override fun message(channel: String, message: String) {
        messages.add(message)
      }
    })
    
    subConnection.subscribe("notifications")
    
    // Publish messages
    pubConnection.publish("notifications", "User logged in")
    pubConnection.publish("notifications", "Order created")
    
    // Wait for messages
    delay(1.seconds)
    
    messages.size shouldBe 2
    messages shouldContain "User logged in"
    messages shouldContain "Order created"
    
    subConnection.unsubscribe("notifications")
  }
}
```

### Expiration and TTL

Test key expiration:

```kotlin
stove {
  redis {
    val connection = client().connect().sync()
    
    // Set with expiration
    connection.setex("temp:data", 5, "temporary-value")
    
    // Check TTL
    val ttl = connection.ttl("temp:data")
    ttl shouldBeGreaterThan 0
    ttl shouldBeLessThanOrEqual 5
    
    // Set expiration on existing key
    connection.set("permanent", "data")
    connection.expire("permanent", 10)
    val newTtl = connection.ttl("permanent")
    newTtl shouldBeGreaterThan 0
    
    // Remove expiration
    connection.persist("permanent")
    val persistedTtl = connection.ttl("permanent")
    persistedTtl shouldBe -1 // No expiration
  }
}
```

### Transactions

Test Redis transactions:

```kotlin
stove {
  redis {
    val connection = client().connect().sync()
    
    connection.multi()
    connection.set("account:1:balance", "1000")
    connection.decrby("account:1:balance", 100)
    connection.incrby("account:2:balance", 100)
    val results = connection.exec()
    
    results.size shouldBe 3
    
    val balance1 = connection.get("account:1:balance")
    balance1 shouldBe "900"
    
    val balance2 = connection.get("account:2:balance")
    balance2 shouldBe "100"
  }
}
```

### Pause and Unpause Container

Test failure scenarios:

```kotlin hl_lines="11 15 19"
stove {
  redis {
    val connection = client().connect().sync()
    
    // Redis is running
    connection.set("test", "value")
    connection.get("test") shouldBe "value"
    
    // Pause container
    pause()
    
    // Operations should fail
    shouldThrow<RedisException> {
      connection.get("test")
    }
    
    // Unpause container
    unpause()
    
    // Wait for recovery
    delay(2.seconds)
    
    // Operations should work again
    val value = connection.get("test")
    value shouldBe "value"
  }
}
```

## Complete Example

Here's a complete caching test example:

```kotlin hl_lines="7 14 22 30"
test("should cache product data in redis") {
  stove {
    val productId = "product-123"
    
    // Product not in cache - verify using client()
    redis {
      val conn = client().connect().sync()
      val cached = conn.get("cache:product:$productId")
      cached shouldBe null
    }
    
    // Fetch from database via API (application should cache the result)
    http {
      get<ProductResponse>("/products/$productId") { product ->
        product.id shouldBe productId
        product.name shouldNotBe null
      }
    }
    
    // Application should have cached the product - verify
    redis {
      val conn = client().connect().sync()
      val cachedData = conn.get("cache:product:$productId")
      cachedData shouldNotBe null
      
      val cachedProduct = objectMapper.readValue(cachedData, ProductResponse::class.java)
      cachedProduct.id shouldBe productId
    }
    
    // Verify TTL is set
    redis {
      val conn = client().connect().sync()
      val ttl = conn.ttl("cache:product:$productId")
      ttl shouldBeGreaterThan 0
      ttl shouldBeLessThanOrEqual 3600
    }
  }
}
```

## Integration with Application

Test application caching behavior:

```kotlin
test("should use redis for session management") {
  stove {
    val sessionId = UUID.randomUUID().toString()
    
    // Create session via API
    http {
      postAndExpectBody<SessionResponse>(
        uri = "/auth/login",
        body = LoginRequest(username = "user", password = "pass").some()
      ) { response ->
        response.status shouldBe 200
        response.body().sessionId shouldBe sessionId
      }
    }
    
    // Verify session in Redis
    redis {
      val connection = client().connect().sync()
      val sessionData = connection.get("session:$sessionId")
      sessionData shouldNotBe null
      
      val session = objectMapper.readValue(sessionData, Session::class.java)
      session.username shouldBe "user"
      session.createdAt shouldNotBe null
    }
    
    // Use session
    http {
      get<UserProfile>(
        uri = "/profile",
        headers = mapOf("X-Session-ID" to sessionId)
      ) { profile ->
        profile.username shouldBe "user"
      }
    }
    
    // Logout
    http {
      postAndExpectBodilessResponse(
        uri = "/auth/logout",
        body = LogoutRequest(sessionId = sessionId).some()
      ) { response ->
        response.status shouldBe 200
      }
    }
    
    // Verify session removed from Redis
    redis {
      val connection = client().connect().sync()
      val sessionData = connection.get("session:$sessionId")
      sessionData shouldBe null
    }
  }
}
```

## Advanced: Custom Extensions

<span data-rn="highlight" data-rn-color="#00968855" data-rn-duration="800">Create reusable extensions for common patterns:</span>

```kotlin
// Custom extension functions
fun RedisSystem.shouldGet(key: String, assertion: (String?) -> Unit): RedisSystem {
  val connection = client().connect().sync()
  val value = connection.get(key)
  assertion(value)
  return this
}

fun RedisSystem.shouldSet(key: String, value: String): RedisSystem {
  val connection = client().connect().sync()
  connection.set(key, value)
  return this
}

// Usage in tests
stove {
  redis {
    shouldSet("user:123", "John Doe")
    shouldGet("user:123") { value ->
      value shouldBe "John Doe"
    }
  }
}
```