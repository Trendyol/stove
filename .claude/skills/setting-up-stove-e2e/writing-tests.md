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

// GET
http {
    get<UserResponse>("/users/123") { user ->
        user.name shouldBe "John"
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
    mockUnary(
        serviceName = "frauddetection.FraudDetectionService",
        methodName = "CheckFraud",
        response = CheckFraudResponse.newBuilder()
            .setIsFraudulent(false)
            .setRiskScore(0.15)
            .build()
    )

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
using<OrderService> {
    val order = getOrderByUserId(userId)
    order shouldNotBe null
    order!!.status shouldBe OrderStatus.CONFIRMED
}
```

## Trace validation

```kotlin
tracing {
    shouldContainSpan("OrderService.processOrder")
    shouldNotHaveFailedSpans()
    executionTimeShouldBeLessThan(500.milliseconds)
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
