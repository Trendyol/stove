# MongoDB

Real MongoDB in a container or wired to existing infra. Document save/get/query, aggregation, transactions via raw `client()` escape hatch.

<a class="open-in-wizard" data-sys="mongodb">Open in setup wizard</a>

<!--{wizard:snippet id=sys.mongodb parts=gradle,configure,test}-->

<div class="stove-tldr" markdown>
<span class="stove-tldr-title">In 30 seconds</span>
Register <code>mongodb { MongodbSystemOptions(...) }</code>. Default collection lives under <code>databaseOptions = DatabaseOptions(default = DefaultDatabase(name, collection))</code>. Test DSL: <code>save(...)</code>, <code>shouldGet&lt;T&gt;(id)</code>, <code>shouldQuery&lt;T&gt;(jsonFilter)</code>, <code>shouldDelete()</code>. Drop into <code>client()</code> for aggregation pipelines or transactions.
</div>

## Configure

```kotlin
Stove().with {
  mongodb {
    MongodbSystemOptions(
      databaseOptions = DatabaseOptions(
        default = DefaultDatabase(name = "testdb", collection = "orders")
      ),
      configureExposedConfiguration = { cfg ->
        listOf("spring.data.mongodb.uri=${cfg.connectionString}")
      }
    )
  }
}.run()
```

| Field | Use |
|---|---|
| `databaseOptions` | Default DB + collection picked when DSL omits them |
| `configureClient` | Customize the `MongoClient` (codecs, pool size) |
| `serde` | Align with your app's mapper |
| `container = MongoContainerOptions(...)` | Tag, registry, raw `containerFn` overrides |
| `configureExposedConfiguration` | Hand `connectionString`, `host`, `port` to AUT |

## DSL

### Save / fetch

```kotlin
stove {
  mongodb {
    save(
      objectId = ObjectId.get().toHexString(),
      instance = Order(id = "1", status = "CREATED"),
      collection = "orders"   // omit to use default
    )

    shouldGet<Order>(objectId = orderHex, collection = "orders") {
      it.status shouldBe "CREATED"
    }
  }
}
```

### Query (JSON filter)

```kotlin
stove {
  mongodb {
    shouldQuery<Order>(
      query = """{ "status": "CREATED", "amount": { "${'$'}gte": 100 } }""",
      collection = "orders"
    ) { orders ->
      orders shouldHaveSize 2
      orders.all { it.amount >= 100 } shouldBe true
    }
  }
}
```

### Delete + not-exist guard

```kotlin
stove {
  mongodb {
    shouldDelete(objectId = orderHex, collection = "orders")
    shouldNotExist(objectId = orderHex, collection = "orders")
  }
}
```

### Aggregation / transactions (raw client)

```kotlin
stove {
  mongodb {
    val totals = client().getDatabase("testdb")
      .getCollection("orders")
      .aggregate(listOf(
        Aggregates.match(Filters.eq("status", "CREATED")),
        Aggregates.group("\$customerId", Accumulators.sum("total", "\$amount"))
      ))
      .toList()

    totals shouldHaveSize 1
  }
}
```

## Migrations

```kotlin
class CreateOrdersIndex : DatabaseMigration<MongodbMigrationContext> {
  override val order = 1
  override suspend fun execute(ctx: MongodbMigrationContext) {
    ctx.client.getDatabase("testdb")
      .getCollection("orders")
      .createIndex(Indexes.ascending("customerId"))
  }
}

mongodb {
  MongodbSystemOptions(/* ... */).migrations {
    register<CreateOrdersIndex>()
  }
}
```

## Complete example

```kotlin
test("place order, verify Mongo doc + Kafka event") {
  stove {
    val orderId = ObjectId.get().toHexString()

    http {
      postAndExpectBody<OrderResponse>(
        "/orders",
        CreateOrderRequest(id = orderId, amount = 99.99).some()
      ) { it.status shouldBe 201 }
    }

    mongodb {
      shouldGet<Order>(objectId = orderId, collection = "orders") {
        it.status shouldBe "CREATED"
        it.amount shouldBe 99.99
      }
    }

    kafka {
      shouldBePublished<OrderCreatedEvent> {
        actual.id == orderId
      }
    }
  }
}
```

## Pitfalls

| Symptom | Fix |
|---|---|
| `Cannot find collection X` | Your code uses a different collection name; mirror exactly |
| ObjectId mismatch | Use `ObjectId.get().toHexString()`; pass the hex everywhere |
| `$` interpreted by Kotlin | Escape inside strings: `"${'$'}gte"` |

## Pairs well with

- [Provided Instances](11-provided-instances.md) for shared CI clusters (prefix collection names)
- [Bridge](10-bridge.md) to verify via the app's own repository when DSL coverage gaps
