# Writing Tests Reference

## Contents
- [HTTP requests](#http-requests)
- [PostgreSQL queries](#postgresql-queries)
- [Kafka assertions](#kafka-assertions)
- [WireMock mocking](#wiremock-mocking)
- [gRPC Mock](#grpc-mock)
- [gRPC Client](#grpc-client)
- [Bridge (DI access)](#bridge-di-access)
- [Trace validation](#trace-validation)
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
