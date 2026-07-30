# WireMock

Mock third-party HTTP services at the network edge. Stubs for every verb, partial-body matching with dot notation, behavior sequences for retry / circuit-breaker tests.

<a class="open-in-wizard" data-mk="wiremock">Open in setup wizard</a>

<!--{wizard:snippet id=sys.wiremock parts=gradle,configure,test}-->

<div class="stove-tldr" markdown>
<span class="stove-tldr-title">In 30 seconds</span>
Register <code>wiremock { WireMockSystemOptions(port = 0) }</code> (port 0 = dynamic, CI-safe). Inject the actual URL into your AUT via <code>configureExposedConfiguration</code>. In tests, call <code>mockGet</code>, <code>mockPost</code>, <code>mockPut</code>, <code>mockPatch</code>, <code>mockDelete</code>, <code>mockHead</code>. For complex matching, use <code>mockPostContaining</code> with dot notation.
</div>

## Configure

```kotlin
Stove().with {
  wiremock {
    WireMockSystemOptions(
      // port = 0 by default (dynamic, CI-safe)
      configureExposedConfiguration = { cfg ->
        // cfg.baseUrl = "http://localhost:<dynamic-port>"
        listOf(
          "payment.service.url=${cfg.baseUrl}",
          "inventory.service.url=${cfg.baseUrl}",
          "notification.service.url=${cfg.baseUrl}"
        )
      }
    )
  }
}.run()
```

!!! warning "External URLs must be configurable in your app"
    WireMock can't intercept hardcoded URLs. Your app must read these as properties, not bake them into client classes. See [Best Practices · External URLs](../best-practices.md#external-urls-must-be-configurable).

### Options

| Field | Default | Use |
|---|---|---|
| `port` | `0` (dynamic) | Fixed port if needed; prefer 0 |
| `configure` | `notifier(ConsoleNotifier(true))` | Custom `WireMockConfiguration` builder |
| `removeStubAfterRequestMatched` | `false` | One-shot stubs |
| `afterStubRemoved` | no-op | Hook after stub eviction |
| `afterRequest` | no-op | Hook after each request |
| `serde` | `StoveSerde.jackson.anyByteArraySerde()` | Pass your app's mapper |
| `configureExposedConfiguration` | empty | Inject WireMock URL into AUT |

## Basic stubs

```kotlin
stove {
  wiremock {
    // GET
    mockGet(
      url = "/api/products/1",
      statusCode = 200,
      responseBody = Product("1", "Laptop", 999.99).some(),
      responseHeaders = mapOf("X-Rate-Limit" to "100")
    )

    // POST
    mockPost(
      url = "/api/orders",
      statusCode = 201,
      requestBody = CreateOrderRequest(items = listOf("item1")).some(),
      responseBody = OrderResponse(orderId = "o-123").some()
    )

    // PUT
    mockPut(
      url = "/api/products/1",
      statusCode = 200,
      requestBody = UpdateProductRequest(price = 899.99).some(),
      responseBody = Product("1", "Updated", 899.99).some()
    )

    // PATCH
    mockPatch(
      url = "/api/users/123",
      statusCode = 200,
      requestBody = mapOf("email" to "new@example.com").some(),
      responseBody = UserResponse(id = "123", email = "new@example.com").some()
    )

    // DELETE / HEAD (typically bodiless)
    mockDelete(url = "/api/products/123", statusCode = 204)
    mockHead(url = "/api/products/exists/123", statusCode = 200)
  }
}
```

## Structured DSL (0.26+)

The structured DSL keeps request matching and response construction separate. String-based verb functions match the URL path, so query parameters can be declared independently. Stub names are inferred from the method and target unless you provide one.

!!! note "API evolution"
    Stove APIs are still evolving and may change in minor releases. When an API changes, the release notes will include a migration guide.

```kotlin
test("creates a payment") {
  stove {
    wiremock {
      mockPost("/api/payments", name = "create payment") {
        request {
          header("X-Tenant", "nl")
          query("dryRun", "false")
          contentTypeJson()
          jsonField("customer.id", "customer-1")
        }
        respond {
          status = 201
          header("X-Service", "payments")
          json(PaymentResponse(paymentId = "payment-1"))
        }
      }
    }
  }
}
```

Request targets are explicit when building a reusable specification:

- `path("/api/payments")` matches the path and ignores undeclared query parameters.
- `exactUrl("/api/payments?dryRun=false")` matches the complete URL.
- `pathRegex("/api/payments/[0-9]+")` matches a path regex.

Request blocks support `header`, `query`, `contentTypeJson`, `jsonEqualTo`, `jsonField`, and `jsonPath`. Response blocks support `json`, `jsonNull`, `rawJson`, `text`, `bytes`, `empty`, headers, status, and delay.

### Reuse a request for stubbing and verification

```kotlin
test("calls the payment service") {
  stove {
    wiremock {
      val createPayment = request(RequestMethod.POST, path("/api/payments")) {
        header("X-Tenant", "nl")
        jsonField("customer.id", "customer-1")
      }

      stub(createPayment) {
        respond {
          status = 202
          text("accepted")
        }
      }

      // Exercise the application.

      shouldHaveBeenCalled(createPayment, exactly(1))
      callsFor(createPayment)
    }
  }
}
```

The same request specification also works with `shouldNotHaveBeenCalled`.

### Managed access to native WireMock

Use `rawStub` when WireMock exposes a feature that the structured DSL does not:

```kotlin
test("uses a native transformer") {
  stove {
    wiremock {
      rawStub("transformed response") {
        get(urlPathEqualTo("/api/transformed"))
          .willReturn(
            aResponse()
              .withStatus(200)
              .withTransformers("response-template")
          )
      }
    }
  }
}
```

The name is optional and otherwise inferred from the mapping. Unlike direct `server()` access, `rawStub` retains Stove reporting, test scoping, verification, and cleanup.

## Advanced matching

For URL patterns, regex bodies, header constraints:

```kotlin
stove {
  wiremock {
    mockGetConfigure(
      url = "/api/search",
      urlPatternFn = { urlPathMatching("/api/search.*") }
    ) { builder, serde ->
      builder
        .withQueryParam("q", matching(".*laptop.*"))
        .willReturn(
          aResponse()
            .withStatus(200)
            .withBody(serde.serialize(SearchResults(items = listOf("a", "b"))))
        )
    }

    mockPostConfigure(
      url = "/api/webhooks",
      urlPatternFn = { urlEqualTo(it) }
    ) { builder, _ ->
      builder
        .withHeader("X-Webhook-Secret", equalTo("secret123"))
        .withRequestBody(containing("event_type"))
        .willReturn(aResponse().withStatus(200).withBody("ok"))
    }
  }
}
```

## Partial body matching (dot notation)

The killer feature. Match only the fields you care about; ignore generated IDs, timestamps, surrounding context.

```kotlin
stove {
  wiremock {
    // Only matches if productId == 123 (other fields ignored)
    mockPostContaining(
      url = "/api/orders",
      requestContaining = mapOf("productId" to 123),
      statusCode = 201,
      responseBody = OrderResponse(orderId = "o-1").some()
    )

    // Multiple fields: AND logic
    mockPostContaining(
      url = "/api/payments",
      requestContaining = mapOf(
        "orderId" to "o-1",
        "amount" to 99.99,
        "currency" to "USD"
      ),
      statusCode = 200,
      responseBody = PaymentResponse(transactionId = "t-1").some()
    )

    // Deep nesting via dot path
    mockPostContaining(
      url = "/api/orders",
      requestContaining = mapOf("order.customer.id" to "cust-123"),
      statusCode = 200,
      responseBody = OrderConfirmation(status = "confirmed").some()
    )

    // Mix levels
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

    // Nested object: partial comparison (extra fields in nested object ignored)
    mockPutContaining(
      url = "/api/config",
      requestContaining = mapOf("settings" to mapOf("enabled" to true)),
      statusCode = 200
    )

    // URL pattern + partial body
    mockPostContaining(
      url = "/api/v[0-9]+/orders",
      requestContaining = mapOf("orderId" to "o-1"),
      statusCode = 200,
      urlPatternFn = { urlPathMatching(it) }
    )
  }
}
```

| Method | Verb |
|---|---|
| `mockPostContaining` | POST |
| `mockPutContaining` | PUT |
| `mockPatchContaining` | PATCH |

Supports primitives, nested maps, arrays, dot paths, and `urlPatternFn` for regex URLs.

## Behavior sequences

The structured DSL describes only observable behaviour; WireMock scenario and state names remain internal. Add transient responses, then finish with the response that stays active:

```kotlin
test("service recovers after two timeouts") {
  stove {
    wiremock {
      mockGet("/api/external-service") {
        behaviour {
          repeat(2) {
            timeout(1.seconds)
          }
          thenAlways {
            status = 200
            json(ServiceResponse(status = "OK"))
          }
        }
      }
    }

    // The first two calls time out; every later call receives the success response.
  }
}
```

Transient HTTP responses use the same response DSL:

```kotlin
test("service succeeds after two unavailable responses") {
  stove {
    wiremock {
      mockPost("/api/payments") {
        behaviour {
          repeat(2) {
            respond {
              status = 503
              json(mapOf("error" to "temporarily unavailable"))
            }
          }
          thenAlways {
            status = 200
            json(mapOf("recovered" to true))
          }
        }
      }
    }
  }
}
```

`thenAlways` is required and remains active for all later matching calls. The existing `behaviourFor` API remains available for tests that need direct `ResponseDefinitionBuilder` access.

## Simulating slow responses

```kotlin
wiremock {
  mockGetConfigure("/slow-endpoint") { builder, _ ->
    builder.willReturn(
      aResponse()
        .withStatus(200)
        .withBody("Response")
        .withFixedDelay(5000)   // 5-second delay
    )
  }
}
```

## Full example: order with three upstream services

```kotlin
test("order creation orchestrates user, product, inventory") {
  stove {
    val userId = "user-123"
    val productId = "product-456"

    wiremock {
      mockGet(
        url = "/users/$userId",
        statusCode = 200,
        responseBody = User(id = userId, name = "John", active = true).some()
      )

      mockGet(
        url = "/products/$productId",
        statusCode = 200,
        responseBody = Product(id = productId, name = "Laptop", price = 999.99).some()
      )

      mockPost(
        url = "/inventory/reserve",
        statusCode = 200,
        requestBody = ReserveStockRequest(productId = productId, quantity = 1).some(),
        responseBody = ReservationResponse(reservationId = "r-1", success = true).some()
      )
    }

    http {
      postAndExpectBody<OrderResponse>(
        uri = "/orders",
        body = CreateOrderRequest(userId = userId, productId = productId, quantity = 1).some()
      ) {
        it.status shouldBe 201
        it.body().status shouldBe "CREATED"
      }
    }

    kafka {
      shouldBePublished<OrderCreatedEvent> {
        actual.userId == userId && actual.productId == productId
      }
    }
  }
}
```

## Pairs well with

- [HTTP Client](05-http.md). Drive your app's API while WireMock stubs the upstream
- [Best Practices](../best-practices.md#external-boundaries). Configurable URLs, error scenarios
- [Recipes · order flow](../recipes/order-flow.md). Full multi-system flow
