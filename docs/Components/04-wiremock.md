# Wiremock

=== "Gradle"

    ``` kotlin
        dependencies {
            testImplementation("com.trendyol:stove-testing-e2e-wiremock:$version")
        }
    ```

## Configure

After getting the library from the maven source, while configuring TestSystem you will have access to `wiremock`
function.

This will start an instance of Wiremock server. You can configure the port of the Wiremock server.

```kotlin
TestSystem()
  .with {
    wiremock {
      WiremockSystemOptions(
        port = 8080,
      )
    }
  }
  .run()
```

### Options

```kotlin
data class WireMockSystemOptions(
  /**
   * Port of wiremock server
   */
  val port: Int = 9090,
  /**
   * Configures wiremock server
   */
  val configure: WireMockConfiguration.() -> WireMockConfiguration = { this.notifier(ConsoleNotifier(true)) },
  /**
   * Removes the stub when request matches/completes
   * Default value is false
   */
  val removeStubAfterRequestMatched: Boolean = false,
  /**
   * Called after stub removed
   */
  val afterStubRemoved: AfterStubRemoved = { _, _ -> },
  /**
   * Called after request handled
   */
  val afterRequest: AfterRequestHandler = { _, _ -> },
  /**
   * ObjectMapper for serialization/deserialization
   */
  val serde: StoveSerde<Any, ByteArray> = StoveSerde.jackson.anyByteArraySerde()
) : SystemOptions
```

## Mocking

Wiremock starts a mock server on the `localhost` with the given port. The important thing is that you use the same port
in your application for your services.

Say, your application calls an external service in your production configuration as:
`http://externalservice.com/api/product/get-all`
you need to replace the **base url** of this an all the external services with the Wiremock host and port:
`http://localhost:9090`

You can either do this in your application configuration, or let Stove send this as a command line argument to your
application.

```kotlin
TestSystem()
  .with {
    wiremock {
      WireMockSystemOptions(
        port = 9090,
      )
    }
    springBoot( // or ktor
      runner = {
        // ...
      },
      withParameters = listOf(
        "externalServiceBaseUrl=http://localhost:9090",
        "otherService1BaseUrl=http://localhost:9090",
        "otherService2BaseUrl=http://localhost:9090"
      )
    )
  }
  .run()
```

All service endpoints will be pointing to the Wiremock server. You can now define the stubs for the services that your
application calls.

## Usage

### GET Requests

Mock GET requests with various configurations:

```kotlin
TestSystem.validate {
  wiremock {
    // Simple GET mock
    mockGet(
      url = "/api/products",
      statusCode = 200,
      responseBody = listOf(
        Product("1", "Laptop", 999.99),
        Product("2", "Mouse", 29.99)
      ).some()
    )

    // GET with custom headers
    mockGet(
      url = "/api/user/profile",
      statusCode = 200,
      responseBody = UserProfile(id = "123", name = "John").some(),
      responseHeaders = mapOf(
        "Content-Type" to "application/json",
        "X-Rate-Limit" to "100"
      )
    )

    // GET returning error
    mockGet(
      url = "/api/products/999",
      statusCode = 404,
      responseBody = ErrorResponse("Product not found").some()
    )
  }
}
```

### POST Requests

Mock POST requests with request/response bodies:

```kotlin
TestSystem.validate {
  wiremock {
    // POST with request and response body
    mockPost(
      url = "/api/orders",
      statusCode = 201,
      requestBody = CreateOrderRequest(items = listOf("item1", "item2")).some(),
      responseBody = OrderResponse(orderId = "order-123", status = "CREATED").some()
    )

    // POST with metadata matching
    mockPost(
      url = "/api/users",
      statusCode = 201,
      requestBody = CreateUserRequest(name = "John").some(),
      responseBody = UserResponse(id = "user-123", name = "John").some(),
      metadata = mapOf("Content-Type" to "application/json")
    )

    // POST returning error
    mockPost(
      url = "/api/orders",
      statusCode = 400,
      requestBody = InvalidOrderRequest().some(),
      responseBody = ValidationError("Invalid order data").some()
    )
  }
}
```

### PUT Requests

Mock PUT requests for updates:

```kotlin
TestSystem.validate {
  wiremock {
    // PUT with full update
    mockPut(
      url = "/api/products/123",
      statusCode = 200,
      requestBody = UpdateProductRequest(name = "Updated Product", price = 899.99).some(),
      responseBody = Product("123", "Updated Product", 899.99).some()
    )

    // PUT with no response body
    mockPut(
      url = "/api/settings/update",
      statusCode = 204,
      requestBody = UpdateSettingsRequest(theme = "dark").some()
    )
  }
}
```

### PATCH Requests

Mock PATCH requests for partial updates:

```kotlin
TestSystem.validate {
  wiremock {
    // PATCH for partial update
    mockPatch(
      url = "/api/users/123",
      statusCode = 200,
      requestBody = mapOf("email" to "newemail@example.com").some(),
      responseBody = UserResponse(id = "123", email = "newemail@example.com").some()
    )
  }
}
```

### DELETE Requests

Mock DELETE requests:

```kotlin
TestSystem.validate {
  wiremock {
    // DELETE returning success
    mockDelete(
      url = "/api/products/123",
      statusCode = 204
    )

    // DELETE with metadata
    mockDelete(
      url = "/api/users/456",
      statusCode = 200,
      metadata = mapOf("Authorization" to "Bearer token123")
    )
  }
}
```

### HEAD Requests

Mock HEAD requests:

```kotlin
TestSystem.validate {
  wiremock {
    mockHead(
      url = "/api/products/exists/123",
      statusCode = 200
    )

    mockHead(
      url = "/api/products/exists/999",
      statusCode = 404
    )
  }
}
```

### Advanced Configuration

For complex scenarios, use the configure methods:

```kotlin
TestSystem.validate {
  wiremock {
    // Advanced GET configuration
    mockGetConfigure(
      url = "/api/search",
      urlPatternFn = { urlPathMatching("/api/search.*") }
    ) { builder, serde ->
      builder
        .withQueryParam("q", matching(".*laptop.*"))
        .willReturn(
          aResponse()
            .withStatus(200)
            .withBody(serde.serialize(SearchResults(items = listOf("item1", "item2"))))
        )
    }

    // Advanced POST configuration
    mockPostConfigure(
      url = "/api/webhooks",
      urlPatternFn = { urlEqualTo(it) }
    ) { builder, serde ->
      builder
        .withHeader("X-Webhook-Secret", equalTo("secret123"))
        .withRequestBody(containing("event_type"))
        .willReturn(
          aResponse()
            .withStatus(200)
            .withBody("Webhook received")
        )
    }
  }
}
```

### Behavioral Mocking

Simulate service behavior changes over multiple calls:

```kotlin
test("service recovers from failure") {
  TestSystem.validate {
    wiremock {
      behaviourFor("/api/external-service", WireMock::get) {
        initially {
          aResponse()
            .withStatus(503)
            .withBody("Service unavailable")
        }
        then {
          aResponse()
            .withStatus(503)
            .withBody("Still unavailable")
        }
        then {
          aResponse()
            .withStatus(200)
            .withHeader("Content-Type", "application/json")
            .withBody(it.serialize(ServiceResponse(status = "OK")))
        }
      }
    }

    http {
      // First call - failure
      getResponse("/api/external-service") { response ->
        response.status shouldBe 503
      }

      // Second call - still failing
      getResponse("/api/external-service") { response ->
        response.status shouldBe 503
      }

      // Third call - success
      get<ServiceResponse>("/api/external-service") { response ->
        response.status shouldBe "OK"
      }
    }
  }
}
```

### Testing Circuit Breaker

Test circuit breaker patterns with WireMock:

```kotlin
test("circuit breaker opens after failures") {
  TestSystem.validate {
    wiremock {
      // Mock service that fails
      mockGet(
        url = "/api/unreliable-service",
        statusCode = 500,
        responseBody = "Internal Server Error".some()
      )
    }

    // Application calls the service multiple times
    repeat(5) {
      http {
        getResponse("/api/call-external") { response ->
          // First few calls fail
          response.status shouldBe 500
        }
      }
    }

    // Update mock to return success
    wiremock {
      mockGet(
        url = "/api/unreliable-service",
        statusCode = 200,
        responseBody = ServiceResponse(status = "OK").some()
      )
    }

    // Circuit breaker should open, need to wait for recovery
    delay(5.seconds)

    http {
      get<ServiceResponse>("/api/call-external") { response ->
        response.status shouldBe "OK"
      }
    }
  }
}
```

## Complete Example

Here's a complete test with multiple external service mocks:

```kotlin
test("should create order with external service validation") {
  TestSystem.validate {
    val userId = "user-123"
    val productId = "product-456"
    val categoryId = 1

    // Mock user service
    wiremock {
      mockGet(
        url = "/users/$userId",
        statusCode = 200,
        responseBody = User(id = userId, name = "John Doe", active = true).some(),
        responseHeaders = mapOf("X-Service" to "UserService")
      )
    }

    // Mock product catalog service
    wiremock {
      mockGet(
        url = "/products/$productId",
        statusCode = 200,
        responseBody = Product(
          id = productId,
          name = "Laptop",
          price = 999.99,
          stock = 10
        ).some()
      )
    }

    // Mock category service
    wiremock {
      mockGet(
        url = "/categories/$categoryId",
        statusCode = 200,
        responseBody = Category(id = categoryId, name = "Electronics", active = true).some()
      )
    }

    // Mock inventory service (POST to reserve stock)
    wiremock {
      mockPost(
        url = "/inventory/reserve",
        statusCode = 200,
        requestBody = ReserveStockRequest(productId = productId, quantity = 1).some(),
        responseBody = ReservationResponse(reservationId = "res-789", success = true).some()
      )
    }

    // Create order via your API
    http {
      postAndExpectBody<OrderResponse>(
        uri = "/orders",
        body = CreateOrderRequest(
          userId = userId,
          productId = productId,
          quantity = 1
        ).some()
      ) { response ->
        response.status shouldBe 201
        response.body().orderId shouldNotBe null
        response.body().status shouldBe "CREATED"
      }
    }

    // Verify order was stored
    postgresql {
      shouldQuery<Order>(
        "SELECT * FROM orders WHERE user_id = ?",
        mapper = { row ->
          Order(
            id = row.long("id"),
            userId = row.string("user_id"),
            productId = row.string("product_id"),
            quantity = row.int("quantity")
          )
        }
      ) { orders ->
        orders.size shouldBe 1
        orders.first().userId shouldBe userId
        orders.first().productId shouldBe productId
      }
    }

    // Verify event was published
    kafka {
      shouldBePublished<OrderCreatedEvent>(atLeastIn = 10.seconds) {
        actual.userId == userId &&
        actual.productId == productId
      }
    }
  }
}
```

## Error Scenarios

Test how your application handles external service failures:

```kotlin
test("should handle external service unavailability") {
  TestSystem.validate {
    // Mock external service returning 503
    wiremock {
      mockGet(
        url = "/external-api/data",
        statusCode = 503,
        responseBody = ErrorResponse("Service temporarily unavailable").some()
      )
    }

    // Your application should handle this gracefully
    http {
      getResponse("/api/fetch-data") { response ->
        response.status shouldBe 503 // or your fallback status
      }
    }
  }
}

test("should handle timeout") {
  TestSystem.validate {
    wiremock {
      mockGetConfigure("/slow-endpoint") { builder, _ ->
        builder.willReturn(
          aResponse()
            .withStatus(200)
            .withBody("Response")
            .withFixedDelay(5000) // 5 second delay
        )
      }
    }

    http {
      getResponse("/api/call-slow-service") { response ->
        // Your application should timeout and handle it
        response.status shouldBe 504 // Gateway timeout
      }
    }
  }
}
```

## Integration Testing

Test complex integrations with multiple services:

```kotlin
test("should orchestrate multiple services") {
  TestSystem.validate {
    val userId = "user-123"

    // Mock authentication service
    wiremock {
      mockPost(
        url = "/auth/validate",
        statusCode = 200,
        requestBody = TokenRequest(token = "jwt-token").some(),
        responseBody = TokenValidation(valid = true, userId = userId).some()
      )
    }

    // Mock permissions service
    wiremock {
      mockGet(
        url = "/permissions/$userId",
        statusCode = 200,
        responseBody = Permissions(
          userId = userId,
          roles = listOf("USER", "ADMIN")
        ).some()
      )
    }

    // Make authenticated request
    http {
      get<SecureData>(
        uri = "/api/secure-data",
        token = "jwt-token".some()
      ) { data ->
        data.accessible shouldBe true
      }
    }
  }
}
```

## Request Verification

Verify that requests were made as expected:

```kotlin
test("should verify request details") {
  TestSystem.validate {
    wiremock {
      mockPost(
        url = "/api/webhook",
        statusCode = 200,
        metadata = mapOf(
          "X-Signature" to "expected-signature"
        )
      )
    }

    // Trigger webhook
    http {
      postAndExpectBodilessResponse(
        uri = "/trigger-webhook",
        body = WebhookTrigger(event = "user.created").some()
      ) { response ->
        response.status shouldBe 200
      }
    }

    // Verify the webhook was called with correct signature
    // (WireMock will only match if headers match)
  }
}
```
