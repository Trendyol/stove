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

Wiremock starts a mock server on `localhost` with the configured port. 

!!! warning "Critical: External Service URLs Must Match WireMock"

    **All external service URLs in your application must be configured to point to the WireMock server.**
    
    This is one of the most common configuration mistakes. If your application's external service URLs 
    don't match WireMock's URL, your mocks won't be hit and tests will fail or timeout.

### URL Configuration

Say your application calls external services in production:

- `http://payment-service.com/api/payments`
- `http://inventory-service.com/api/stock`
- `http://notification-service.com/api/notify`

For testing, **all** these base URLs must be replaced with the WireMock URL (e.g., `http://localhost:9090`).

You can pass these as application parameters:

```kotlin
TestSystem()
  .with {
    wiremock {
      WireMockSystemOptions(
        port = 9090,
      )
    }
    springBoot( // or ktor
      runner = { params ->
        com.myapp.run(params)
      },
      withParameters = listOf(
        // All external services point to WireMock
        "payment.service.url=http://localhost:9090",
        "inventory.service.url=http://localhost:9090",
        "notification.service.url=http://localhost:9090"
      )
    )
  }
  .run()
```

### Application Configuration Tips

Make your external service URLs configurable in your application:

=== "Spring Boot (application.yml)"

    ```yaml
    external:
      payment-service:
        url: ${PAYMENT_SERVICE_URL:http://payment-service.com}
      inventory-service:
        url: ${INVENTORY_SERVICE_URL:http://inventory-service.com}
    ```

=== "Ktor"

    ```kotlin
    val paymentUrl = environment.config.propertyOrNull("payment.service.url")
        ?.getString() ?: "http://payment-service.com"
    ```

Then in your tests, Stove passes the WireMock URL through parameters, overriding the defaults.

All service endpoints will be pointing to the WireMock server. You can now define the stubs for the services that your
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

### Partial Body Matching

When you only need to match specific fields in a request body without specifying the entire payload, 
use the `*Containing` methods. This is useful when:

- The request body has fields you don't control (timestamps, generated IDs)
- You only care about matching certain business-critical fields
- The request body structure is complex but you need to match a single unique identifier

#### Basic Partial Matching

Match requests containing specific fields:

```kotlin
TestSystem.validate {
  wiremock {
    // Only matches requests where productId = 123, ignores other fields
    mockPostContaining(
      url = "/api/orders",
      requestContaining = mapOf("productId" to 123),
      statusCode = 201,
      responseBody = OrderResponse(orderId = "order-123").some()
    )
  }
}

// This request WILL match (extra fields are ignored):
// POST /api/orders
// {"productId": 123, "quantity": 5, "userId": "user-456", "timestamp": "2024-01-01T00:00:00Z"}
```

#### Multiple Field Matching

Match multiple fields at once:

```kotlin
TestSystem.validate {
  wiremock {
    mockPostContaining(
      url = "/api/payments",
      requestContaining = mapOf(
        "orderId" to "order-123",
        "amount" to 99.99,
        "currency" to "USD"
      ),
      statusCode = 200,
      responseBody = PaymentResponse(transactionId = "txn-789").some()
    )
  }
}
```

#### Deep Nested Matching with Dot Notation

Match specific fields deep within nested JSON structures using dot notation:

```kotlin
TestSystem.validate {
  wiremock {
    // Match a single field deep in the JSON structure
    mockPostContaining(
      url = "/api/orders",
      requestContaining = mapOf("order.customer.id" to "cust-123"),
      statusCode = 200,
      responseBody = OrderConfirmation(status = "confirmed").some()
    )
  }
}

// This request WILL match:
// POST /api/orders
// {
//   "order": {
//     "id": "order-999",
//     "customer": {
//       "id": "cust-123",           <-- Only this field is matched
//       "name": "John Doe",
//       "email": "john@example.com"
//     },
//     "items": [...]
//   },
//   "metadata": {...}
// }
```

#### Multiple Deep Nested Fields

Match multiple fields at different levels of nesting:

```kotlin
TestSystem.validate {
  wiremock {
    mockPostContaining(
      url = "/api/checkout",
      requestContaining = mapOf(
        "order.customer.id" to "cust-123",
        "order.payment.method" to "credit_card",
        "metadata.source" to "mobile_app"
      ),
      statusCode = 200,
      responseBody = CheckoutResponse(success = true).some()
    )
  }
}
```

#### Nested Object Matching

Match nested objects with partial comparison (extra fields in nested objects are ignored):

```kotlin
TestSystem.validate {
  wiremock {
    // Match if the "settings" object contains at least {enabled: true}
    mockPutContaining(
      url = "/api/config",
      requestContaining = mapOf(
        "settings" to mapOf("enabled" to true)
      ),
      statusCode = 200
    )
  }
}

// This request WILL match (extra fields in settings are ignored):
// PUT /api/config
// {
//   "settings": {
//     "enabled": true,      <-- Matched
//     "level": 5,           <-- Ignored
//     "features": [...]     <-- Ignored
//   }
// }
```

#### Available Partial Matching Methods

| Method | HTTP Method | Description |
|--------|-------------|-------------|
| `mockPostContaining` | POST | Partial body matching for POST requests |
| `mockPutContaining` | PUT | Partial body matching for PUT requests |
| `mockPatchContaining` | PATCH | Partial body matching for PATCH requests |

All methods support:

- **Simple values**: strings, numbers, booleans
- **Dot notation**: `"order.customer.id"` for deep nested access
- **Nested objects**: `mapOf("user" to mapOf("id" to 123))`
- **Arrays**: `mapOf("tags" to listOf("important", "urgent"))`
- **URL patterns**: Use `urlPatternFn` parameter for regex URL matching

#### URL Pattern with Partial Matching

Combine URL patterns with partial body matching:

```kotlin
TestSystem.validate {
  wiremock {
    mockPostContaining(
      url = "/api/v[0-9]+/orders",
      requestContaining = mapOf("orderId" to "order-123"),
      statusCode = 200,
      urlPatternFn = { urlPathMatching(it) }  // Enable regex URL matching
    )
  }
}

// Matches: POST /api/v1/orders, POST /api/v2/orders, etc.
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
