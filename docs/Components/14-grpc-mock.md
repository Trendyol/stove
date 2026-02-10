# <span data-rn="underline" data-rn-color="#ff9800">gRPC Mock</span>

`stove-grpc-mock` provides a native gRPC mock server for testing gRPC service integrations. Unlike WireMock-based solutions, this implementation provides <span data-rn="highlight" data-rn-color="#00968855" data-rn-duration="800">**full support for all gRPC RPC types**</span> without external dependency conflicts.

## Features

| Feature | Support |
|---------|---------|
| Unary RPC | ✅ Full support |
| Server Streaming | ✅ Full support |
| Client Streaming | ✅ Full support |
| Bidirectional Streaming | ✅ Full support |
| Error responses | ✅ Full support |
| Request matching | ✅ Full support |
| **Authentication** | ✅ Full support |
| Multiple services | ✅ Same port |

## Installation

```kotlin
dependencies {
  testImplementation("com.trendyol:stove-grpc-mock:$stoveVersion")
}
```

## Configuration

By default, gRPC Mock uses a **dynamic port** (port = 0), which lets the system pick an available port automatically. This avoids port conflicts, especially in CI environments.

```kotlin hl_lines="4-5 11-12"
Stove()
  .with {
    grpcMock {
      GrpcMockSystemOptions(
        // port = 0 by default (dynamic port)
        removeStubAfterRequestMatched = true, // optional, default false
        configureExposedConfiguration = { cfg ->
          // cfg.host = "localhost"
          // cfg.port = <dynamic-port>
          listOf(
            "grpcService.host=${cfg.host}",
            "grpcService.port=${cfg.port}"
          )
        }
      )
    }
    // Your application configuration - gRPC settings are auto-injected
    ktor(
      runner = { parameters -> run(parameters) }
    )
  }
```

### Using Fixed Port (Not Recommended for CI)

If you need a specific port:

```kotlin
grpcMock {
  GrpcMockSystemOptions(
    port = 9090  // Fixed port
  )
}
```

!!! tip "Dynamic Ports Avoid CI Conflicts"

    Using `port = 0` (the default) lets the system pick an available port automatically. This is essential in CI environments where:
    
    - Multiple test runs may execute in parallel
    - Other services might already be using common ports
    - You get "Address already in use" errors with fixed ports
    
    The `configureExposedConfiguration` callback receives the actual port after the server starts.

## Usage

### Mocking Unary Calls

```kotlin hl_lines="4-5 16"
test("should mock unary gRPC call") {
  stove {
    grpcMock {
      mockUnary(
        serviceName = "greeting.GreeterService",
        methodName = "SayHello",
        response = HelloResponse.newBuilder()
          .setMessage("Hello from mock!")
          .build()
      )
    }
    
    // Your test that triggers the gRPC call
    http {
      get("/api/greet/World") { response ->
        response.body shouldContain "Hello from mock!"
      }
    }
  }
}
```

### Mocking with Request Matching

```kotlin hl_lines="3 6 15 18"
grpcMock {
  // Match specific request
  mockUnary(
    serviceName = "users.UserService",
    methodName = "GetUser",
    requestMatcher = RequestMatcher.ExactMessage(
      GetUserRequest.newBuilder().setUserId("123").build()
    ),
    response = GetUserResponse.newBuilder()
      .setName("John Doe")
      .build()
  )
  
  // Custom matcher
  mockUnary(
    serviceName = "users.UserService",
    methodName = "GetUser",
    requestMatcher = RequestMatcher.Custom { bytes ->
      // Parse and inspect request bytes
      val request = GetUserRequest.parseFrom(bytes)
      request.userId.startsWith("vip-")
    },
    response = GetUserResponse.newBuilder()
      .setName("VIP User")
      .build()
  )
}
```

### Mocking Server Streaming

```kotlin
grpcMock {
  mockServerStream(
    serviceName = "streaming.ItemService",
    methodName = "ListItems",
    responses = listOf(
      Item.newBuilder().setId("1").setName("Item 1").build(),
      Item.newBuilder().setId("2").setName("Item 2").build(),
      Item.newBuilder().setId("3").setName("Item 3").build()
    )
  )
}
```

### Mocking Client Streaming

```kotlin
grpcMock {
  mockClientStream(
    serviceName = "upload.UploadService",
    methodName = "UploadChunks",
    response = UploadResponse.newBuilder()
      .setTotalSize(1024)
      .setSuccess(true)
      .build()
  )
}
```

> **Note:** For client streaming, the `requestMatcher` is evaluated against **only the first request** in the stream. This is because stub matching happens before the full stream is received. If you need to validate all requests in a client stream, use the bidirectional streaming mock with a custom handler instead.

### Mocking Bidirectional Streaming

```kotlin
grpcMock {
  mockBidiStream(
    serviceName = "chat.ChatService",
    methodName = "Chat"
  ) { requestFlow ->
    // Transform each request into a response
    requestFlow.map { requestBytes ->
      val request = ChatMessage.parseFrom(requestBytes)
      ChatMessage.newBuilder()
        .setMessage("Echo: ${request.message}")
        .build()
    }
  }
}
```

### Mocking Error Responses

```kotlin hl_lines="2 4-5 11 16-17"
grpcMock {
  mockError(
    serviceName = "users.UserService",
    methodName = "GetUser",
    status = Status.Code.NOT_FOUND,
    message = "User not found"
  )
  
  // With request matching
  mockError(
    serviceName = "users.UserService",
    methodName = "DeleteUser",
    requestMatcher = RequestMatcher.ExactMessage(
      DeleteUserRequest.newBuilder().setUserId("admin").build()
    ),
    status = Status.Code.PERMISSION_DENIED,
    message = "Cannot delete admin user"
  )
}
```

## Authentication Support

`stove-grpc-mock` provides full support for mocking authenticated gRPC calls.

### Bearer Token Authentication

```kotlin
grpcMock {
  mockUnary(
    serviceName = "secure.SecureService",
    methodName = "GetSecret",
    metadataMatcher = MetadataMatcher.BearerToken("valid-token-123"),
    response = SecretResponse.newBuilder()
      .setData("confidential")
      .build()
  )
}

// Call with proper token
grpc {
  channel<SecureServiceGrpcKt.SecureServiceCoroutineStub>(
    metadata = mapOf("authorization" to "Bearer valid-token-123")
  ) {
    val response = getSecret(request)  // Works!
  }
}
```

### Custom Header Matching

```kotlin
grpcMock {
  mockUnary(
    serviceName = "api.ApiService",
    methodName = "GetData",
    metadataMatcher = MetadataMatcher.HasHeader("x-api-key", "secret-key"),
    response = DataResponse.newBuilder().build()
  )
}
```

### Require Any Authentication

```kotlin
grpcMock {
  // Matches any request with a non-empty authorization header
  mockUnary(
    serviceName = "auth.AuthService",
    methodName = "GetProfile",
    metadataMatcher = MetadataMatcher.RequiresAuth,
    response = ProfileResponse.newBuilder().build()
  )
}
```

### Combined Matchers

```kotlin
grpcMock {
  mockUnary(
    serviceName = "multi.MultiAuthService",
    methodName = "GetResource",
    metadataMatcher = MetadataMatcher.All(
      MetadataMatcher.BearerToken("valid-token"),
      MetadataMatcher.HasHeader("x-tenant-id", "tenant-123")
    ),
    response = ResourceResponse.newBuilder().build()
  )
}
```

### Authenticated Streaming

```kotlin
grpcMock {
  mockServerStream(
    serviceName = "secure.DataService",
    methodName = "StreamData",
    metadataMatcher = MetadataMatcher.BearerToken("stream-token"),
    responses = listOf(data1, data2, data3)
  )
  
  mockClientStream(
    serviceName = "secure.UploadService",
    methodName = "Upload",
    metadataMatcher = MetadataMatcher.BearerToken("upload-token"),
    response = UploadResponse.newBuilder().setSuccess(true).build()
  )
  
  mockBidiStream(
    serviceName = "secure.ChatService",
    methodName = "Chat",
    metadataMatcher = MetadataMatcher.BearerToken("chat-token")
  ) { requestFlow ->
    requestFlow.map { parseAndRespond(it) }
  }
}
```

### Testing Auth Failures

```kotlin
test("should reject unauthenticated request") {
  stove {
    grpcMock {
      // Only accepts valid token
      mockUnary(
        serviceName = "secure.SecureService",
        methodName = "GetSecret",
        metadataMatcher = MetadataMatcher.BearerToken("valid-token"),
        response = SecretResponse.newBuilder().build()
      )
    }
    
    grpc {
      // Call WITHOUT token - fails with UNIMPLEMENTED (no matching stub)
      channel<SecureServiceGrpcKt.SecureServiceCoroutineStub> {
        val exception = shouldThrow<StatusException> {
          getSecret(request)
        }
        exception.status.code shouldBe Status.Code.UNIMPLEMENTED
      }
      
      // Call WITH wrong token - also fails
      channel<SecureServiceGrpcKt.SecureServiceCoroutineStub>(
        metadata = mapOf("authorization" to "Bearer wrong-token")
      ) {
        val exception = shouldThrow<StatusException> {
          getSecret(request)
        }
        exception.status.code shouldBe Status.Code.UNIMPLEMENTED
      }
    }
  }
}
```

## Multiple gRPC Services

The mock server can handle **multiple services on the same port**. Simply register stubs for different services:

```kotlin
Stove()
  .with {
    grpcMock {
      GrpcMockSystemOptions(port = 9090)
    }
    ktor(
      withParameters = listOf(
        // All services point to the same mock server
        "featureToggle.host=localhost",
        "featureToggle.port=9090",
        "pricing.host=localhost", 
        "pricing.port=9090",
        "inventory.host=localhost",
        "inventory.port=9090"
      ),
      runner = { parameters -> run(parameters) }
    )
  }
```

Then mock each service in your tests:

```kotlin
test("should handle multiple gRPC services") {
  stove {
    grpcMock {
      // Service 1: Feature Toggle
      mockUnary(
        serviceName = "featuretoggle.FeatureToggleService",
        methodName = "IsFeatureEnabled",
        response = IsFeatureEnabledResponse.newBuilder()
          .setEnabled(true)
          .build()
      )
      
      // Service 2: Pricing
      mockUnary(
        serviceName = "pricing.PricingService",
        methodName = "CalculatePrice",
        response = CalculatePriceResponse.newBuilder()
          .setFinalPrice(29.99)
          .build()
      )
      
      // Service 3: Inventory (error case)
      mockError(
        serviceName = "inventory.InventoryService",
        methodName = "CheckStock",
        status = Status.Code.UNAVAILABLE,
        message = "Inventory service is down"
      )
    }
    
    // Test your application logic
    http {
      post("/api/checkout", body = checkoutRequest.some()) { response ->
        // Assert based on mocked responses
      }
    }
  }
}
```

## Stub Removal Options

By default, stubs persist across requests. You can configure automatic removal:

```kotlin
grpcMock {
  GrpcMockSystemOptions(
    port = 9090,
    removeStubAfterRequestMatched = true // Remove stub after first match
  )
}
```

This is useful when testing retry logic or different responses for sequential calls.

## Direct gRPC Client Testing

You can also test gRPC calls directly using the `grpc` system:

```kotlin
test("should call mocked gRPC service directly") {
  stove {
    grpcMock {
      mockUnary(
        serviceName = "greeting.GreeterService",
        methodName = "SayHello",
        response = HelloResponse.newBuilder()
          .setMessage("Hello!")
          .build()
      )
    }
    
    grpc {
      channel<GreeterServiceGrpcKt.GreeterServiceCoroutineStub> {
        val response = sayHello(
          HelloRequest.newBuilder().setName("Test").build()
        )
        response.message shouldBe "Hello!"
      }
    }
  }
}
```

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                    Your Application                          │
├─────────────────────┬───────────────────────────────────────┤
│  ServiceA Client    │      ServiceB Client                  │
│  (port 9090)        │      (port 9090)                      │
└────────────┬────────┴───────────────┬───────────────────────┘
             │                        │
             ▼                        ▼
┌─────────────────────────────────────────────────────────────┐
│              stove-grpc-mock Server (port 9090)             │
│  ┌────────────────────────────────────────────────────────┐ │
│  │              Dynamic Handler Registry                   │ │
│  │  Routes by: serviceName/methodName                      │ │
│  ├─────────────────────┬──────────────────────────────────┤ │
│  │ serviceA.*          │    serviceB.*                     │ │
│  │ → stub responses    │    → stub responses               │ │
│  └─────────────────────┴──────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────┘
```

## Comparison with WireMock gRPC

| Feature | stove-grpc-mock | WireMock gRPC |
|---------|-----------------|---------------|
| Unary RPC | ✅ | ✅ |
| Server Streaming | ✅ Full | ⚠️ First response only |
| Client Streaming | ✅ | ❌ Not supported |
| Bidi Streaming | ✅ | ❌ Not supported |
| Proto descriptors | Not needed | Required |
| Dependency conflicts | None | Shaded protobuf issues |
| Setup complexity | Simple | Requires descriptor generation |

## Best Practices

1. **Register stubs before triggering calls** - Stubs must be registered before your application makes gRPC calls.

2. **Use specific request matchers** - When testing different scenarios, use `RequestMatcher.ExactMessage` to ensure the right stub is matched.

3. **Test error scenarios** - Use `mockError()` to test how your application handles gRPC failures.

4. **Multiple services, single port** - <span data-rn="highlight" data-rn-color="#4caf5044" data-rn-duration="800">Point all gRPC clients to the same mock server port</span> for simpler configuration.

5. **Use `removeStubAfterRequestMatched`** - Enable this when testing retry logic or sequential calls with different responses.
