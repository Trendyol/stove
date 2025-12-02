# gRPC

=== "Gradle"

    ``` kotlin
        dependencies {
            testImplementation("com.trendyol:stove-testing-e2e-grpc:$version")
        }
    ```

## Configure

After getting the library from the maven source, while configuring TestSystem you will have access to `grpc`:

```kotlin
TestSystem()
  .with {
    grpc {
      GrpcSystemOptions(
        host = "localhost",
        port = 50051
      )
    }
  }
  .run()
```

### Configuration Options

```kotlin
data class GrpcSystemOptions(
  /**
   * The gRPC server host.
   */
  val host: String,

  /**
   * The gRPC server port.
   */
  val port: Int,

  /**
   * Whether to use plaintext (no TLS). Default is true for testing.
   */
  val usePlaintext: Boolean = true,

  /**
   * Request timeout duration (default: 30 seconds).
   */
  val timeout: Duration = 30.seconds,

  /**
   * List of client interceptors for logging, auth, tracing, etc.
   */
  val interceptors: List<ClientInterceptor> = emptyList(),

  /**
   * Default metadata (headers) to send with every request.
   */
  val metadata: Map<String, String> = emptyMap(),

  /**
   * Factory function for creating the underlying ManagedChannel.
   */
  val createChannel: (host: String, port: Int) -> ManagedChannel = { h, p ->
    defaultChannelBuilder(h, p, usePlaintext, timeout, interceptors, metadata)
  },

  /**
   * Factory function for creating Wire's GrpcClient with resources.
   */
  val createWireClient: (host: String, port: Int) -> WireClientResources = { h, p ->
    defaultWireGrpcClient(h, p, timeout, metadata)
  }
)
```

### With Authentication

```kotlin
grpc {
  GrpcSystemOptions(
    host = "localhost",
    port = 50051,
    metadata = mapOf("authorization" to "Bearer $token"),
    interceptors = listOf(LoggingInterceptor())
  )
}
```

## Usage

Stove's gRPC module supports multiple gRPC providers through a provider-agnostic design:

- **Wire clients** (`wireClient<T>`) - For Wire-generated clients
- **Typed channel** (`channel<T>`) - For any stub with a Channel constructor
- **Custom providers** (`withEndpoint`) - For any gRPC library
- **Raw channel** (`rawChannel`) - For advanced scenarios

### Wire Clients

For services generated with [Wire](https://github.com/square/wire):

```kotlin
TestSystem.validate {
  grpc {
    wireClient<GreeterServiceClient> {
      val response = SayHello().execute(HelloRequest(name = "World"))
      response.message shouldBe "Hello, World!"
    }
  }
}
```

### Typed Channel (grpc-kotlin and Wire stubs)

For any stub that takes a Channel constructor. This works with both grpc-kotlin generated stubs and Wire-generated stubs:

```kotlin
TestSystem.validate {
  grpc {
    channel<GreeterServiceStub> {
      // 'this' is the stub - direct method calls
      val response = sayHello(HelloRequest(name = "World"))
      response.message shouldBe "Hello, World!"
    }
  }
}
```

#### With Per-Call Metadata

```kotlin
TestSystem.validate {
  grpc {
    channel<GreeterServiceStub>(
      metadata = mapOf("authorization" to "Bearer custom-token")
    ) {
      val response = sayHello(HelloRequest(name = "Authenticated"))
      response.message shouldBe "Hello, Authenticated!"
    }
  }
}
```

### Custom Providers

For any other gRPC library, use `withEndpoint` with a factory function:

```kotlin
TestSystem.validate {
  grpc {
    withEndpoint({ host, port -> 
      // Create your client however you want
      MyCustomGrpcClient.connect(host, port)
    }) {
      // 'this' is your client
      this.call() shouldBe expected
    }
  }
}
```

### Raw Channel Access

For advanced scenarios where you need full control:

```kotlin
TestSystem.validate {
  grpc {
    rawChannel { channel ->
      // Full control over channel
      val stub = GreeterGrpc.newBlockingStub(channel)
      val response = stub.sayHello(request)
      response.message shouldBe "Hello!"
    }
  }
}
```

## Streaming

All streaming types work naturally with Kotlin coroutines.

### Server Streaming

```kotlin
TestSystem.validate {
  grpc {
    channel<StreamServiceStub> {
      val responses = serverStream(request).toList()
      
      responses.size shouldBe 5
      responses[0].message shouldBe "Item 0"
      responses[4].message shouldBe "Item 4"
    }
  }
}
```

### Client Streaming

```kotlin
TestSystem.validate {
  grpc {
    channel<StreamServiceStub> {
      val requestFlow = flow {
        emit(Request(message = "First"))
        emit(Request(message = "Second"))
        emit(Request(message = "Third"))
      }
      
      val response = clientStream(requestFlow)
      response.message shouldBe "Received: First, Second, Third"
      response.count shouldBe 3
    }
  }
}
```

### Bidirectional Streaming

```kotlin
TestSystem.validate {
  grpc {
    channel<StreamServiceStub> {
      val requestFlow = flow {
        emit(Request(message = "A"))
        emit(Request(message = "B"))
      }
      
      val responses = bidiStream(requestFlow).toList()
      responses.size shouldBe 2
      responses[0].message shouldBe "Echo: A"
      responses[1].message shouldBe "Echo: B"
    }
  }
}
```

## Wire Client Details

### Direct GrpcClient Access

```kotlin
TestSystem.validate {
  grpc {
    rawWireClient { client ->
      val service = client.create(GreeterServiceClient::class)
      val response = service.SayHello().execute(HelloRequest(name = "Direct"))
      response.message shouldBe "Hello, Direct!"
    }
  }
}
```

### Wire Client with Custom OkHttp Configuration

```kotlin
TestSystem.validate {
  grpc {
    withEndpoint({ host, port ->
      val okHttpClient = OkHttpClient.Builder()
        .protocols(listOf(Protocol.H2_PRIOR_KNOWLEDGE))
        .addInterceptor { chain ->
          val request = chain.request().newBuilder()
            .addHeader("authorization", "Bearer my-token")
            .build()
          chain.proceed(request)
        }
        .build()
      
      GrpcClient.Builder()
        .client(okHttpClient)
        .baseUrl("http://$host:$port")
        .build()
        .create(GreeterServiceClient::class)
    }) {
      val response = SayHello().execute(HelloRequest(name = "Custom"))
      response.message shouldBe "Hello, Custom!"
    }
  }
}
```

## Authentication & Interceptors

### Global Interceptors

```kotlin
class LoggingInterceptor : ClientInterceptor {
  override fun <ReqT, RespT> interceptCall(
    method: MethodDescriptor<ReqT, RespT>,
    callOptions: CallOptions,
    next: Channel
  ): ClientCall<ReqT, RespT> {
    println("Calling: ${method.fullMethodName}")
    return next.newCall(method, callOptions)
  }
}

TestSystem()
  .with {
    grpc {
      GrpcSystemOptions(
        host = "localhost",
        port = 50051,
        interceptors = listOf(LoggingInterceptor())
      )
    }
  }
```

### Per-Call Metadata

```kotlin
TestSystem.validate {
  grpc {
    // Metadata is applied via interceptor automatically
    channel<SecureServiceStub>(
      metadata = mapOf(
        "authorization" to "Bearer jwt-token",
        "x-request-id" to "12345"
      )
    ) {
      val response = secureEndpoint(request)
      response.success shouldBe true
    }
  }
}
```

## Error Handling

### Testing Authentication Errors

```kotlin
TestSystem.validate {
  grpc {
    // Wire client - throws GrpcException
    wireClient<SecureServiceClient> {
      val exception = shouldThrow<GrpcException> {
        SecureCall().execute(Request(message = "Hello"))
      }
      exception.grpcStatus shouldBe GrpcStatus.UNAUTHENTICATED
    }
    
    // grpc-kotlin - throws StatusException
    channel<SecureServiceStub> {
      val exception = shouldThrow<StatusException> {
        secureCall(request)
      }
      exception.status.code shouldBe Status.Code.UNAUTHENTICATED
    }
  }
}
```

### Testing Not Found

```kotlin
TestSystem.validate {
  grpc {
    channel<UserServiceStub> {
      val exception = shouldThrow<StatusException> {
        getUser(GetUserRequest(id = 999999))
      }
      exception.status.code shouldBe Status.Code.NOT_FOUND
    }
  }
}
```

## Complete Example

Here's a complete test example with various gRPC operations:

```kotlin
test("should perform gRPC operations") {
  TestSystem.validate {
    // Test unary call
    grpc {
      channel<UserServiceStub> {
        val response = createUser(CreateUserRequest(name = "John", email = "john@example.com"))
        response.id shouldNotBe null
        response.name shouldBe "John"
      }
    }

    // Test with authentication
    grpc {
      channel<UserServiceStub>(
        metadata = mapOf("authorization" to "Bearer admin-token")
      ) {
        val users = listUsers(ListUsersRequest(limit = 10)).toList()
        users.size shouldBeGreaterThan 0
      }
    }

    // Test error handling
    grpc {
      channel<UserServiceStub> {
        shouldThrow<StatusException> {
          getUser(GetUserRequest(id = -1))
        }.status.code shouldBe Status.Code.INVALID_ARGUMENT
      }
    }
  }
}
```

## Integration with Other Components

### gRPC + Database

```kotlin
TestSystem.validate {
  // Create via gRPC
  var userId: Long = 0
  grpc {
    channel<UserServiceStub> {
      val response = createUser(CreateUserRequest(name = "John"))
      userId = response.id
    }
  }

  // Verify in database
  postgresql {
    shouldQuery(
      query = "SELECT * FROM users WHERE id = $userId",
      mapper = { row -> User(row.long("id"), row.string("name")) }
    ) { users ->
      users.size shouldBe 1
      users.first().name shouldBe "John"
    }
  }
}
```

### gRPC + Kafka

```kotlin
TestSystem.validate {
  // Trigger event via gRPC
  grpc {
    channel<OrderServiceStub> {
      createOrder(CreateOrderRequest(amount = 100.0))
    }
  }

  // Verify event was published
  kafka {
    shouldBePublished<OrderCreatedEvent>(atLeastIn = 10.seconds) {
      actual.amount == 100.0
    }
  }
}
```

## Provider Support

| Provider | DSL Method | Notes |
|----------|------------|-------|
| Wire | `wireClient<T>` | For Wire-generated service clients |
| grpc-kotlin | `channel<T>` | Works with any stub with Channel constructor |
| Wire stubs | `channel<T>` | Works with Wire server stubs |
| Custom | `withEndpoint` | Any library with factory function |
| Advanced | `rawChannel` | Direct ManagedChannel access |
| Advanced | `rawWireClient` | Direct Wire GrpcClient access |
