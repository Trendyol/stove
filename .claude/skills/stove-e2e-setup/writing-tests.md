# Writing Tests Reference

All e2e tests use the `stove { }` entry point. Each system is accessed via its DSL function.

## HTTP requests

```kotlin
// GET with typed response
http {
    get<UserResponse>("/users/123") { user ->
        user.name shouldBe "John"
    }
}

// POST with body and response
http {
    postAndExpectBody<OrderResponse>(
        uri = "/orders",
        body = CreateOrderRequest(userId = "u1", amount = 99.99).some()
    ) { response ->
        response.status shouldBe 201
        response.body().orderId shouldNotBe null
    }
}

// DELETE
http {
    deleteAndExpectBodilessResponse("/users/123") { response ->
        response.status shouldBe 204
    }
}
```

## PostgreSQL queries

```kotlin
postgresql {
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

// Verify consumed (Spring Kafka only)
kafka {
    shouldBeConsumed<OrderCreatedEvent>(atLeastIn = 20.seconds) {
        actual.orderId == orderId
    }
}

// Publish a message for consumption testing
kafka {
    publish(
        topic = "order-events",
        message = OrderCreated(orderId = "456", amount = 100.0),
        key = "order-456".some()
    )
}

// Verify failed message handling
kafka {
    shouldBeFailed<FailingEvent>(atLeastIn = 10.seconds) {
        actual.id == 5L && reason is BusinessException
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

// Behavioral mocking (sequential responses)
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
    mockUnary(
        serviceName = "frauddetection.FraudDetectionService",
        methodName = "CheckFraud",
        response = CheckFraudResponse.newBuilder()
            .setIsFraudulent(false)
            .setRiskScore(0.15)
            .build()
    )

    // Error response
    mockError(
        serviceName = "users.UserService",
        methodName = "GetUser",
        status = Status.Code.NOT_FOUND,
        message = "User not found"
    )
}
```

## gRPC Client (testing your own gRPC server)

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

## Bridge (DI container access)

```kotlin
// Single bean
using<OrderService> {
    val order = getOrderByUserId(userId)
    order shouldNotBe null
    order!!.status shouldBe OrderStatus.CONFIRMED
}

// Multiple beans
using<UserService, OrderService> { userService, orderService ->
    val user = userService.findById(123)
    val orders = orderService.findByUserId(123)
    orders.size shouldBeGreaterThan 0
}
```

## Trace validation

```kotlin
tracing {
    shouldContainSpan("OrderService.processOrder")
    shouldContainSpan("PaymentClient.charge")
    shouldNotHaveFailedSpans()
    executionTimeShouldBeLessThan(500.milliseconds)
}
```

## Multi-system test example

```kotlin
test("complete order flow") {
    stove {
        val userId = "user-${UUID.randomUUID()}"
        val productId = "macbook-pro-16"
        val amount = 2499.99
        var orderId: String? = null

        // 1. Mock external gRPC service
        grpcMock {
            mockUnary(
                serviceName = "frauddetection.FraudDetectionService",
                methodName = "CheckFraud",
                response = CheckFraudResponse.newBuilder()
                    .setIsFraudulent(false).build()
            )
        }

        // 2. Mock external REST APIs
        wiremock {
            mockGet("/inventory/$productId", 200,
                responseBody = InventoryResponse(productId, true, 10).some())
            mockPost("/payments/charge", 200,
                responseBody = PaymentResult(true, "txn-123", amount).some())
        }

        // 3. Call our API
        http {
            postAndExpectBody<OrderResponse>(
                uri = "/api/orders",
                body = CreateOrderRequest(userId, productId, amount).some()
            ) { response ->
                response.status shouldBe 201
                response.body().status shouldBe "CONFIRMED"
                orderId = response.body().orderId
            }
        }

        // 4. Verify database state
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

        // 5. Verify Kafka events
        kafka {
            shouldBePublished<OrderCreatedEvent>(10.seconds) {
                actual.userId == userId && actual.productId == productId
            }
        }

        // 6. Test our gRPC server
        grpc {
            channel<OrderQueryServiceGrpcKt.OrderQueryServiceCoroutineStub> {
                val response = getOrder(
                    GetOrderRequest.newBuilder().setOrderId(orderId!!).build()
                )
                response.order.status shouldBe "CONFIRMED"
            }
        }

        // 7. Access application beans
        using<OrderService> {
            val order = getOrderByUserId(userId)
            order!!.status shouldBe OrderStatus.CONFIRMED
        }
    }
}
```

## Anti-patterns

| Anti-Pattern | Do Instead |
|---|---|
| `Thread.sleep(5000)` | `shouldBePublished<Event>(atLeastIn = 10.seconds) { ... }` |
| Hardcoded IDs (`"order-123"`) | `UUID.randomUUID().toString()` |
| Shared mutable state between tests | Independent tests with unique data |
| Only checking `response.status shouldBe 200` | Assert on response body, DB state, events |
| Calling real external services | Use WireMock / gRPC Mock |
| Configuring Stove per test class | Single `AbstractProjectConfig` for all tests |
