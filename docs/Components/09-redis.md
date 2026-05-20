# Redis

Real Redis in a container or wired to an existing instance. Stove exposes direct Lettuce client access, so tests can use the same Redis primitives your app depends on: strings, hashes, lists, sets, sorted sets, pub/sub, and transactions.

<a class="open-in-wizard" data-sys="redis">Open in setup wizard</a>

<!--{wizard:snippet id=sys.redis parts=gradle,configure,test}-->

<div class="stove-tldr" markdown>
<span class="stove-tldr-title">In 30 seconds</span>
Register <code>redis { RedisOptions(...) }</code>. Inside <code>stove { redis { } }</code>, call <code>client().connect().sync()</code> for the Lettuce sync API. The same client supports async (<code>.async()</code>) and pub/sub (<code>client().connectPubSub()</code>). There is no high-level Redis assertion DSL; raw Lettuce is the API.
</div>

## Configure

```kotlin
Stove().with {
  redis {
    RedisOptions(
      configureExposedConfiguration = { cfg ->
        listOf(
          "spring.data.redis.url=${cfg.url}",
          "spring.data.redis.host=${cfg.host}",
          "spring.data.redis.port=${cfg.port}"
        )
      }
    )
  }
}.run()
```

## DSL by data structure

### Strings

```kotlin
stove {
  redis {
    val sync = client().connect().sync()
    sync.set("user:1", "Alice")
    sync.get("user:1") shouldBe "Alice"
    sync.expire("user:1", 60)   // TTL seconds
  }
}
```

### Hashes

```kotlin
stove {
  redis {
    val sync = client().connect().sync()
    sync.hset("user:1", mapOf("name" to "Alice", "email" to "a@x.com"))
    sync.hget("user:1", "email") shouldBe "a@x.com"
  }
}
```

### Lists / Sets / Sorted sets

```kotlin
stove {
  redis {
    val sync = client().connect().sync()

    sync.lpush("queue:tasks", "task-1", "task-2")
    sync.rpop("queue:tasks") shouldBe "task-1"

    sync.sadd("tags:order:1", "urgent", "high-value")
    sync.smembers("tags:order:1") shouldContain "urgent"

    sync.zadd("leaderboard", 100.0, "player-1")
    sync.zrange("leaderboard", 0, -1) shouldHaveSize 1
  }
}
```

### Async + pipelining

```kotlin
stove {
  redis {
    val async = client().connect().async()
    val futures = (1..100).map { async.set("k:$it", "v") }
    async.flushCommands()
    futures.awaitAll()
  }
}
```

### Pub/Sub

```kotlin
stove {
  redis {
    val pubsub = client().connectPubSub()
    val received = mutableListOf<String>()

    pubsub.addListener(object : RedisPubSubAdapter<String, String>() {
      override fun message(channel: String, message: String) {
        received.add(message)
      }
    })
    pubsub.sync().subscribe("notifications")

    client().connect().sync().publish("notifications", "hello")

    eventually(5.seconds) { received shouldContain "hello" }
  }
}
```

### Transactions

```kotlin
stove {
  redis {
    val sync = client().connect().sync()
    sync.multi()
    sync.set("a", "1")
    sync.set("b", "2")
    sync.exec()
  }
}
```

## Migrations

```kotlin
class SeedConfig : DatabaseMigration<RedisMigrationContext> {
  override val order = 1
  override suspend fun execute(ctx: RedisMigrationContext) {
    ctx.client.connect().sync().set("config:flag", "enabled")
  }
}

redis {
  RedisOptions(/* ... */).migrations { register<SeedConfig>() }
}
```

## Complete example

```kotlin
test("cache populated after order create") {
  stove {
    val orderId = UUID.randomUUID().toString()

    http {
      postAndExpectBody<OrderResponse>(
        "/orders",
        CreateOrderRequest(id = orderId).some()
      ) { it.status shouldBe 201 }
    }

    redis {
      client().connect().sync().get("order:$orderId") shouldNotBe null
    }
  }
}
```

## Pitfalls

| Symptom | Fix |
|---|---|
| Async futures hang | Call `flushCommands()` after batching |
| Pub/sub listener never fires | Subscribe before publishing; use `eventually { }` for the assert |
| TTL not applied | Use `setex` or `expire` after `set` |

## Pairs well with

- [Provided Instances](11-provided-instances.md) for shared Redis (prefix keys with run ID)
- [Best Practices · isolation](../best-practices.md#shared-infra-isolation-ci)
