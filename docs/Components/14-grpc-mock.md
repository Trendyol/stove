# gRPC Mock

Mock external gRPC services. Unary + all streaming modes. Request matchers, auth-aware metadata matchers, dynamic port for CI safety.

<a class="open-in-wizard" data-mk="grpc-mock">Open in setup wizard</a>

<!--{wizard:snippet id=sys.grpc-mock parts=gradle,configure,test}-->

<div class="stove-tldr" markdown>
<span class="stove-tldr-title">In 30 seconds</span>
Register <code>grpcMock { GrpcMockSystemOptions(port = 0) }</code> (port 0 = dynamic). Mock with <code>mockUnary("Svc", "Method", matcher, response)</code>. Streaming variants: <code>mockServerStream</code>, <code>mockClientStream</code>, <code>mockBidiStream</code>. Errors via <code>mockError(status, message)</code>. Match per-call metadata for auth scenarios.
</div>

## Configure

```kotlin
Stove().with {
  grpcMock {
    GrpcMockSystemOptions(
      port = 0,                              // dynamic, CI-safe
      removeStubAfterRequestMatched = false  // true = one-shot stubs
    )
  }
}.run()
```

## Mocks

### Unary

```kotlin
stove {
  grpcMock {
    mockUnary(
      serviceName = "com.acme.InventoryService",
      methodName = "Reserve",
      requestMatcher = RequestMatcher.ExactMessage(
        ReserveRequest.newBuilder()
          .setProductId("p-1")
          .setQuantity(1)
          .build()
      ),
      response = ReserveResponse.newBuilder()
        .setReservationId("r-1")
        .setSuccess(true)
        .build()
    )
  }
}
```

### Custom matcher (raw bytes)

```kotlin
mockUnary(
  serviceName = "com.acme.AuditService",
  methodName = "Log",
  requestMatcher = RequestMatcher.Custom { bytes ->
    bytes.size > 0 && AuditEvent.parseFrom(bytes).level == "ERROR"
  },
  response = LogAck.newBuilder().setOk(true).build()
)
```

### Server stream

```kotlin
mockServerStream(
  serviceName = "com.acme.NotificationService",
  methodName = "Subscribe",
  requestMatcher = RequestMatcher.ExactMessage(SubscribeRequest.newBuilder().setUserId("u1").build()),
  responses = listOf(
    Notification.newBuilder().setMessage("hi").build(),
    Notification.newBuilder().setMessage("bye").build()
  )
)
```

### Client stream

Matching applies to the **first** message in the stream.

```kotlin
mockClientStream(
  serviceName = "com.acme.UploadService",
  methodName = "Upload",
  firstMessageMatcher = RequestMatcher.Custom { bytes ->
    UploadChunk.parseFrom(bytes).fileName == "products.csv"
  },
  response = UploadAck.newBuilder().setReceived(100).build()
)
```

### Bidi stream

```kotlin
mockBidiStream(
  serviceName = "com.acme.ChatService",
  methodName = "Chat",
  handler = { requests ->
    requests.map { req ->
      ChatMessage.newBuilder().setText("echo: ${req.text}").build()
    }
  }
)
```

### Errors

```kotlin
mockError(
  serviceName = "com.acme.InventoryService",
  methodName = "Reserve",
  status = Status.NOT_FOUND,
  message = "product not stocked"
)
```

## Auth-aware matchers

`metadataMatcher` lets you stub differently based on headers / tokens.

```kotlin
mockUnary(
  serviceName = "com.acme.SecureService",
  methodName = "GetData",
  metadataMatcher = MetadataMatcher.All(
    MetadataMatcher.RequiresAuth,
    MetadataMatcher.HasHeader("x-tenant", "acme"),
    MetadataMatcher.BearerToken("user-jwt")
  ),
  requestMatcher = RequestMatcher.ExactMessage(GetDataRequest.getDefaultInstance()),
  response = DataResponse.newBuilder().setValue("ok").build()
)
```

Built-in matchers:

| Matcher | Use |
|---|---|
| `BearerToken("jwt")` | `Authorization: Bearer jwt` |
| `HasHeader("x-y", "z")` | exact header value |
| `RequiresAuth` | any `Authorization` header |
| `All(...)` | AND |

## Multiple services on one port

Several services can register on the same mock instance, no extra config:

```kotlin
stove {
  grpcMock {
    mockUnary("com.acme.InventoryService", "Reserve", /* ... */)
    mockUnary("com.acme.PaymentService", "Charge",   /* ... */)
  }
}
```

## Complete example

```kotlin
test("order checkout flow uses inventory + payment mocks") {
  stove {
    grpcMock {
      mockUnary(
        serviceName = "com.acme.InventoryService",
        methodName = "Reserve",
        requestMatcher = RequestMatcher.ExactMessage(
          ReserveRequest.newBuilder().setProductId("p-1").build()
        ),
        response = ReserveResponse.newBuilder().setReservationId("r-1").build()
      )

      mockUnary(
        serviceName = "com.acme.PaymentService",
        methodName = "Charge",
        requestMatcher = RequestMatcher.ExactMessage(
          ChargeRequest.newBuilder().setAmount(99.99).build()
        ),
        response = ChargeResponse.newBuilder().setTransactionId("t-1").build()
      )
    }

    http {
      postAndExpectBody<OrderResponse>("/orders", orderReq.some()) {
        it.status shouldBe 201
        it.body().reservationId shouldBe "r-1"
        it.body().transactionId shouldBe "t-1"
      }
    }
  }
}
```

## Pairs well with

- [gRPC Client](12-grpc.md) for asserting against the real gRPC services
- [WireMock](04-wiremock.md) for HTTP-mocked upstreams in the same flow
