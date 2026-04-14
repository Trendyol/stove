# Writing Tests Reference

## Contents
- [HTTP requests](#http-requests)
- [HTTP streaming](#http-streaming)
- [PostgreSQL queries](#postgresql-queries)
- [MySQL queries](#mysql-queries)
- [MSSQL queries](#mssql-queries)
- [Cassandra assertions](#cassandra-assertions)
- [MongoDB assertions](#mongodb-assertions)
- [Redis assertions](#redis-assertions)
- [Elasticsearch assertions](#elasticsearch-assertions)
- [Couchbase assertions](#couchbase-assertions)
- [Kafka assertions](#kafka-assertions)
- [WireMock mocking](#wiremock-mocking)
- [gRPC Mock](#grpc-mock)
- [gRPC Client](#grpc-client)
- [Bridge (DI access)](#bridge-di-access)
- [Trace validation](#trace-validation)
- [Keyed system tests](#keyed-system-tests)
- [Smoke testing (providedApplication)](#smoke-testing-providedapplication)
- [Multi-system test](#multi-system-test)
- [Anti-patterns](#anti-patterns)

All tests use the `stove { }` entry point.

## HTTP requests

```kotlin
// POST with typed response
http {
    postAndExpectBody<OrderResponse>(
        uri = "/orders",
        body = CreateOrderRequest(userId = "u1", amount = 99.99).some()
    ) { response ->
        response.status shouldBe 201
        response.body().orderId shouldNotBe null
    }
}

// POST expecting JSON directly
http {
    postAndExpectJson<OrderResponse>("/orders") {
        CreateOrderRequest(userId = "u1", amount = 99.99)
    } { order ->
        order.id shouldNotBe null
    }
}

// GET
http {
    get<UserResponse>("/users/123") { user ->
        user.name shouldBe "John"
    }
}

// GET with full response (status + headers + body)
http {
    getResponse<UserResponse>("/users/123") { response ->
        response.status shouldBe 200
        response.headers["Content-Type"] shouldContain "application/json"
        response.body().id shouldBe 123
    }
}

// GET list
http {
    getMany<ProductResponse>("/products", queryParams = mapOf("page" to "1")) { products ->
        products.size shouldBe 10
    }
}

// PUT
http {
    putAndExpectBody<ProductResponse>(
        uri = "/products/456",
        body = UpdateProductRequest(price = 899.99).some()
    ) { response ->
        response.status shouldBe 200
        response.body().price shouldBe 899.99
    }
}

// DELETE
http {
    deleteAndExpectBodilessResponse("/users/123") { response ->
        response.status shouldBe 204
    }
}

// Multipart upload
http {
    postMultipartAndExpectResponse<UploadResponse>(
        uri = "/products/import",
        body = listOf(
            StoveMultiPartContent.Text("name", "Laptop"),
            StoveMultiPartContent.File("file", "data.csv", csvBytes, MediaType.APPLICATION_OCTET_STREAM_VALUE)
        )
    ) { response ->
        response.status shouldBe 200
    }
}

// WebSocket
http {
    webSocket("/chat") {
        send("Hello!")
        val response = receiveText()
        response shouldBe "Echo: Hello!"
    }

    // Collect multiple messages
    webSocket("/events") {
        val messages = collectTexts(count = 5, timeout = 10.seconds)
        messages.size shouldBe 5
    }

    // Streaming with Flow
    webSocket("/stream") {
        incomingTexts().take(10).toList().size shouldBe 10
    }
}
```

## HTTP streaming

For JSON streaming (NDJSON) endpoints, use Flow-based extensions on `HttpStatement`:

```kotlin
http {
    // Read NDJSON stream line by line, transform each line
    val items = client().prepareGet("/api/events/stream").readJsonTextStream { line ->
        StoveSerde.jackson.default.readValue(line, EventResponse::class.java)
    }.toList()

    items.size shouldBeGreaterThan 0

    // Read stream as ByteReadChannel for binary processing
    client().prepareGet("/api/binary/stream").readJsonContentStream { channel ->
        channel.readRemaining().readText()
    }.toList().shouldNotBeEmpty()
}

// Serialize items to NDJSON for request body
val body = StoveSerde.jackson.anyByteArraySerde().serializeToStreamJson(
    listOf(Event("e1"), Event("e2"), Event("e3"))
)
```

## PostgreSQL queries

```kotlin
postgresql {
    // Execute DDL/DML
    shouldExecute(
        """
        INSERT INTO products (name, price) VALUES ('Laptop', 999.99)
        """.trimIndent()
    )

    // Query with typed mapper
    shouldQuery<OrderRow>(
        query = "SELECT * FROM orders WHERE user_id = '$userId'",
        mapper = { row ->
            OrderRow(
                id = row.string("id"),
                userId = row.string("user_id"),
                amount = row.double("amount"),
                status = row.string("status")
            )
        }
    ) { orders ->
        orders.size shouldBe 1
        orders.first().status shouldBe "CONFIRMED"
    }
}
```

## MySQL queries

Same API as PostgreSQL — uses `shouldExecute` and `shouldQuery` with a row mapper:

```kotlin
mysql {
    shouldExecute("INSERT INTO products (name, price) VALUES ('Laptop', 999.99)")

    shouldQuery<ProductRow>(
        query = "SELECT * FROM products WHERE name = 'Laptop'",
        mapper = { row -> ProductRow(row.string("name"), row.double("price")) }
    ) { products ->
        products.size shouldBe 1
    }
}
```

## MSSQL queries

Same API as PostgreSQL/MySQL:

```kotlin
mssql {
    shouldExecute("INSERT INTO orders (id, status) VALUES ('o1', 'NEW')")

    shouldQuery<OrderRow>(
        query = "SELECT * FROM orders WHERE id = 'o1'",
        mapper = { row -> OrderRow(row.string("id"), row.string("status")) }
    ) { orders ->
        orders.first().status shouldBe "NEW"
    }
}
```

## Cassandra assertions

```kotlin
cassandra {
    // Execute CQL
    shouldExecute("INSERT INTO orders (id, user_id, status) VALUES ('o1', 'u1', 'NEW')")

    // Query with ResultSet assertion
    shouldQuery("SELECT * FROM orders WHERE id = 'o1'") { resultSet ->
        val row = resultSet.one()!!
        row.getString("status") shouldBe "NEW"
    }

    // Execute with BoundStatement
    shouldExecute(session().prepare("DELETE FROM orders WHERE id = ?").bind("o1"))

    // Query with BoundStatement
    shouldQuery(session().prepare("SELECT * FROM orders WHERE id = ?").bind("o1")) { rs ->
        rs.one() shouldBe null
    }

    // Simulate downtime
    pause()
    // ... test resilience ...
    unpause()
}

// Direct session access
cassandra {
    session().execute("TRUNCATE orders")
}
```

## MongoDB assertions

```kotlin
mongodb {
    // Save a document
    save(Order(id = "o1", userId = "u1", amount = 99.99))

    // Save to specific collection
    save(Order(id = "o2", userId = "u2", amount = 50.0), collection = "archived_orders")

    // Get by ObjectId
    shouldGet<Order>(objectId = "o1") { order ->
        order.amount shouldBe 99.99
    }

    // Query with filter string
    shouldQuery<Order>(query = """{ "userId": "u1" }""") { orders ->
        orders.size shouldBe 1
        orders.first().status shouldBe "NEW"
    }

    // Delete
    shouldDelete(objectId = "o1")

    // Verify deletion
    shouldNotExist(objectId = "o1")

    // Simulate downtime
    pause()
    unpause()
}

// Direct client access
mongodb {
    client().getDatabase("testdb").getCollection("orders").drop()
}
```

## Redis assertions

Redis uses the Lettuce client directly via `client()`:

```kotlin
redis {
    // All operations via the Lettuce RedisClient
    val connection = client().connect()
    val commands = connection.sync()

    commands.set("order:o1", """{"status":"NEW"}""")
    commands.get("order:o1") shouldNotBe null
    commands.del("order:o1")

    connection.close()
}

// Simulate downtime
redis {
    pause()
    // ... test resilience ...
    unpause()
}
```

## Elasticsearch assertions

```kotlin
elasticsearch {
    // Save a document
    save(id = "p1", instance = Product("p1", "Laptop", 999.99), index = "products")

    // Get by key
    shouldGet<Product>(index = "products", key = "p1") { product ->
        product.name shouldBe "Laptop"
    }

    // Query with JSON string
    shouldQuery<Product>(
        query = """{ "match": { "name": "Laptop" } }""",
        index = "products"
    ) { products ->
        products.size shouldBe 1
    }

    // Query with Elasticsearch Query DSL object
    shouldQuery<Product>(
        query = Query.of { q -> q.match { m -> m.field("name").query("Laptop") } }
    ) { products ->
        products.shouldNotBeEmpty()
    }

    // Delete
    shouldDelete(key = "p1", index = "products")

    // Verify deletion
    shouldNotExist(key = "p1", index = "products")

    // Simulate downtime
    pause()
    unpause()
}

// Direct client access
elasticsearch {
    client().indices().create { it.index("new-index") }
}
```

## Couchbase assertions

```kotlin
couchbase {
    // Save to default collection
    saveToDefaultCollection(id = "o1", instance = Order("o1", "u1", 99.99))

    // Save to specific collection
    save(collection = "archived", id = "o2", instance = Order("o2", "u2", 50.0))

    // Get by key (default collection)
    shouldGet<Order>(key = "o1") { order ->
        order.amount shouldBe 99.99
    }

    // Get from specific collection
    shouldGet<Order>(collection = "archived", key = "o2") { order ->
        order.userId shouldBe "u2"
    }

    // N1QL query
    shouldQuery<Order>(query = "SELECT * FROM `test-bucket` WHERE userId = 'u1'") { orders ->
        orders.size shouldBe 1
    }

    // Delete (default collection)
    shouldDelete(key = "o1")

    // Delete from specific collection
    shouldDelete(collection = "archived", key = "o2")

    // Verify deletion
    shouldNotExist(key = "o1")
    shouldNotExist(collection = "archived", key = "o2")

    // Simulate downtime
    pause()
    unpause()
}

// Direct cluster/bucket access
couchbase {
    cluster().queryIndexes().createPrimaryIndex("test-bucket")
    bucket().defaultCollection().upsert("doc1", JsonObject.create())
}
```

## Kafka assertions

```kotlin
// Verify published
kafka {
    shouldBePublished<OrderCreatedEvent>(atLeastIn = 10.seconds) {
        actual.orderId == orderId && actual.amount == 99.99
    }
}

// Verify consumed (stove-spring-kafka only)
kafka {
    shouldBeConsumed<OrderCreatedEvent>(atLeastIn = 20.seconds) {
        actual.orderId == orderId
    }
}

// Publish a message
kafka {
    publish(
        topic = "order-events",
        message = OrderCreated(orderId = "456", amount = 100.0),
        key = "order-456".some()
    )
}

// Verify failed handling
kafka {
    shouldBeFailed<FailingEvent>(atLeastIn = 10.seconds) {
        actual.id == 5L && reason is BusinessException
    }
}

// Verify retries (stove-spring-kafka only)
kafka {
    shouldBeRetried<FailingEvent>(atLeastIn = 1.minutes, times = 3) {
        actual.id == "789"
    }
}

// Access message metadata
kafka {
    shouldBePublished<OrderCreatedEvent>(atLeastIn = 10.seconds) {
        actual.orderId == orderId &&
        metadata.topic == "order-events" &&
        metadata.headers["correlation-id"] != null
    }
}
```

## WireMock mocking

```kotlin
wiremock {
    mockGet(
        url = "/inventory/$productId",
        statusCode = 200,
        responseBody = InventoryResponse(available = true).some()
    )

    mockPost(
        url = "/payments/charge",
        statusCode = 200,
        responseBody = PaymentResult(success = true).some()
    )
}

// PUT, PATCH, DELETE mocks
wiremock {
    mockPut(url = "/products/123", statusCode = 200,
        responseBody = Product("123", "Updated", 899.99).some())
    mockPatch(url = "/users/123", statusCode = 200,
        requestBody = mapOf("email" to "new@example.com").some())
    mockDelete(url = "/products/123", statusCode = 204)
    mockHead(url = "/products/exists/123", statusCode = 200)
}

// Partial body matching — match specific fields, ignore the rest
wiremock {
    mockPostContaining(
        url = "/api/orders",
        requestContaining = mapOf("productId" to 123),
        statusCode = 201,
        responseBody = OrderResponse(orderId = "order-123").some()
    )

    // Deep nested matching with dot notation
    mockPostContaining(
        url = "/api/checkout",
        requestContaining = mapOf(
            "order.customer.id" to "cust-123",
            "order.payment.method" to "credit_card"
        ),
        statusCode = 200
    )
}

// Sequential responses (behavioral mocking)
wiremock {
    behaviourFor("/api/service", WireMock::get) {
        initially { aResponse().withStatus(503) }
        then { aResponse().withStatus(503) }
        then { aResponse().withStatus(200).withBody(it.serialize(result)) }
    }
}
```

## gRPC Mock

```kotlin
grpcMock {
    // Unary
    mockUnary(
        serviceName = "frauddetection.FraudDetectionService",
        methodName = "CheckFraud",
        response = CheckFraudResponse.newBuilder()
            .setIsFraudulent(false).setRiskScore(0.15).build()
    )

    // With request matching
    mockUnary(
        serviceName = "users.UserService",
        methodName = "GetUser",
        requestMatcher = RequestMatcher.ExactMessage(
            GetUserRequest.newBuilder().setUserId("123").build()
        ),
        response = GetUserResponse.newBuilder().setName("John").build()
    )

    // With authentication
    mockUnary(
        serviceName = "secure.SecureService",
        methodName = "GetSecret",
        metadataMatcher = MetadataMatcher.BearerToken("valid-token"),
        response = SecretResponse.newBuilder().setData("confidential").build()
    )

    // Server streaming
    mockServerStream(
        serviceName = "streaming.ItemService",
        methodName = "ListItems",
        responses = listOf(item1, item2, item3)
    )

    // Bidirectional streaming
    mockBidiStream(
        serviceName = "chat.ChatService",
        methodName = "Chat"
    ) { requestFlow ->
        requestFlow.map { bytes ->
            val req = ChatMessage.parseFrom(bytes)
            ChatMessage.newBuilder().setMessage("Echo: ${req.message}").build()
        }
    }

    // Error
    mockError(
        serviceName = "users.UserService",
        methodName = "GetUser",
        status = Status.Code.NOT_FOUND,
        message = "User not found"
    )
}
```

## gRPC Client

For testing your own gRPC server:

```kotlin
grpc {
    channel<OrderQueryServiceGrpcKt.OrderQueryServiceCoroutineStub> {
        val response = getOrder(
            GetOrderRequest.newBuilder().setOrderId(orderId).build()
        )
        response.found shouldBe true
        response.order.status shouldBe "CONFIRMED"
    }
}
```

## Bridge (DI access)

```kotlin
// Single bean
using<OrderService> {
    val order = getOrderByUserId(userId)
    order shouldNotBe null
    order!!.status shouldBe OrderStatus.CONFIRMED
}

// Multiple beans (up to 5 supported)
using<UserService, OrderService> { userService, orderService ->
    val user = userService.findById(123)
    val orders = orderService.findByUserId(123)
    orders.size shouldBeGreaterThan 0
}

using<A, B, C> { a, b, c -> /* ... */ }
```

## Trace validation

```kotlin
tracing {
    // Span assertions
    shouldContainSpan("OrderService.processOrder")
    shouldContainSpanMatching { it.operationName.contains("Repository") }
    shouldNotContainSpan("AdminService.delete")
    shouldNotHaveFailedSpans()
    shouldHaveFailedSpan("PaymentGateway.charge")
    shouldHaveSpanWithAttribute("http.method", "GET")

    // Performance assertions
    executionTimeShouldBeLessThan(500.milliseconds)
    spanCountShouldBeAtLeast(5)

    // Debugging
    println(renderTree())    // Hierarchical trace view
    println(renderSummary()) // Compact summary
}
```

## Keyed system tests

Access keyed systems by passing the `SystemKey` to the validation DSL:

```kotlin
// Given keys defined in setup:
// object AppDb : SystemKey
// object AnalyticsDb : SystemKey
// object PaymentService : SystemKey

test("should write to both databases") {
    stove {
        http {
            postAndExpectBody<OrderResponse>(
                uri = "/orders",
                body = CreateOrderRequest("u1", 99.99).some()
            ) { it.status shouldBe 201 }
        }

        // Assert against the app database
        postgresql(AppDb) {
            shouldQuery<OrderRow>(
                query = "SELECT * FROM orders WHERE user_id = 'u1'",
                mapper = { row -> OrderRow(row.string("id"), row.string("status")) }
            ) { it.size shouldBe 1 }
        }

        // Assert against the analytics database
        postgresql(AnalyticsDb) {
            shouldQuery<AnalyticsRow>(
                query = "SELECT * FROM events WHERE user_id = 'u1'",
                mapper = { row -> AnalyticsRow(row.string("event_type")) }
            ) { it.first().eventType shouldBe "ORDER_CREATED" }
        }
    }
}

// Keyed WireMock and HTTP
test("should call payment and inventory services") {
    stove {
        wiremock(PaymentService) {
            mockPost("/charge", 200, PaymentResult(true).some())
        }
        wiremock(InventoryService) {
            mockGet("/stock/item-1", 200, StockResponse(10).some())
        }

        http {
            postAndExpectBody<OrderResponse>("/orders", body = order.some()) {
                it.status shouldBe 201
            }
        }
    }
}
```

All systems support keyed access: `postgresql(key)`, `mysql(key)`, `mssql(key)`, `cassandra(key)`, `mongodb(key)`, `redis(key)`, `elasticsearch(key)`, `couchbase(key)`, `kafka(key)`, `wiremock(key)`, `grpcMock(key)`, `grpc(key)`, `http(key)`.

## Smoke testing (providedApplication)

With `providedApplication()`, test a remote/deployed application without starting it locally. No `Bridge`/`using<T>` — only infrastructure assertions:

```kotlin
test("staging smoke test — order flow") {
    stove {
        val userId = "smoke-${UUID.randomUUID()}"

        // Hit the remote API
        http {
            postAndExpectBody<OrderResponse>(
                uri = "/api/orders",
                body = CreateOrderRequest(userId, 49.99).some()
            ) { response ->
                response.status shouldBe 201
            }
        }

        // Verify side effects in the remote database
        postgresql(AppDb) {
            shouldQuery<OrderRow>(
                query = "SELECT * FROM orders WHERE user_id = '$userId'",
                mapper = { row -> OrderRow(row.string("id"), row.string("status")) }
            ) { orders ->
                orders.size shouldBe 1
                orders.first().status shouldBe "CONFIRMED"
            }
        }

        // Verify Kafka event on the remote cluster
        kafka {
            shouldBePublished<OrderCreatedEvent>(10.seconds) {
                actual.userId == userId
            }
        }
    }
}
```

## Multi-system test

```kotlin
test("complete order flow") {
    stove {
        val userId = "user-${UUID.randomUUID()}"
        var orderId: String? = null

        grpcMock {
            mockUnary(
                serviceName = "frauddetection.FraudDetectionService",
                methodName = "CheckFraud",
                response = CheckFraudResponse.newBuilder()
                    .setIsFraudulent(false).build()
            )
        }

        wiremock {
            mockGet("/inventory/macbook", 200,
                responseBody = InventoryResponse("macbook", true, 10).some())
            mockPost("/payments/charge", 200,
                responseBody = PaymentResult(true, "txn-123", 2499.99).some())
        }

        http {
            postAndExpectBody<OrderResponse>(
                uri = "/api/orders",
                body = CreateOrderRequest(userId, "macbook", 2499.99).some()
            ) { response ->
                response.status shouldBe 201
                orderId = response.body().orderId
            }
        }

        postgresql {
            shouldQuery<OrderRow>(
                query = "SELECT * FROM orders WHERE user_id = '$userId'",
                mapper = { row ->
                    OrderRow(row.string("id"), row.string("user_id"),
                        row.string("product_id"), row.double("amount"),
                        row.string("status"))
                }
            ) { orders ->
                orders.size shouldBe 1
                orders.first().status shouldBe "CONFIRMED"
            }
        }

        kafka {
            shouldBePublished<OrderCreatedEvent>(10.seconds) {
                actual.userId == userId
            }
        }

        grpc {
            channel<OrderQueryServiceGrpcKt.OrderQueryServiceCoroutineStub> {
                val response = getOrder(
                    GetOrderRequest.newBuilder().setOrderId(orderId!!).build()
                )
                response.order.status shouldBe "CONFIRMED"
            }
        }

        using<OrderService> {
            getOrderByUserId(userId)!!.status shouldBe OrderStatus.CONFIRMED
        }
    }
}
```

## Anti-patterns

| Don't | Do |
|---|---|
| `Thread.sleep(5000)` | `shouldBePublished<Event>(atLeastIn = 10.seconds) { ... }` |
| Hardcoded IDs `"order-123"` | `UUID.randomUUID().toString()` |
| Shared mutable state | Independent tests with unique data |
| Only assert `status shouldBe 200` | Assert response body, DB state, events |
| Call real external services | Use WireMock / gRPC Mock |
| Configure Stove per test class | Single `AbstractProjectConfig` |
