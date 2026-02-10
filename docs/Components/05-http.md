# HttpClient

=== "Gradle"

    ``` kotlin
        dependencies {
            testImplementation("com.trendyol:stove-http:$version")
        }
    ```

## Configure

Once you've added the dependency, you'll have access to the `httpClient` function when configuring Stove:

```kotlin hl_lines="3 5"
Stove()
  .with {
    httpClient {
      HttpClientSystemOptions(
        baseUrl = "http://localhost:8080",
      )
    }
  }
  .run()
```

The other options that you can set are:
```kotlin
data class HttpClientSystemOptions(
  /**
   * Base URL of the HTTP client.
   */
  val baseUrl: String,

  /**
   * Content converter for the HTTP client. Default is JacksonConverter. You can use GsonConverter or any other converter.
   * If you want to use your own converter, you can implement ContentConverter interface.
   */
  val contentConverter: ContentConverter = JacksonConverter(StoveSerde.jackson.default),

  /**
   * Timeout for the HTTP client. Default is 30 seconds.
   */
  val timeout: Duration = 30.seconds,

  /**
   * Create client function for the HTTP client. Default is jsonHttpClient.
   */
  val createClient: () -> io.ktor.client.HttpClient = { jsonHttpClient(timeout, contentConverter) }
)
```

## Usage

### GET Requests

Making GET requests with various options:

```kotlin hl_lines="4 10 20 25"
stove {
  http {
    // Simple GET request with type-safe response
    get<UserResponse>("/users/123") { user ->
      user.id shouldBe 123
      user.name shouldBe "John Doe"
    }

    // GET with query parameters
    get<String>("/api/index", queryParams = mapOf("keyword" to "search-term")) { response ->
      response shouldContain "search-term"
    }

    // GET with headers
    get<UserProfile>("/profile", headers = mapOf("X-Custom-Header" to "value")) { profile ->
      profile.email shouldNotBe null
    }

    // GET with authentication token
    get<SecureData>("/secure-endpoint", token = "jwt-token".some()) { data ->
      data.isAuthorized shouldBe true
    }

    // GET multiple items (list response)
    getMany<ProductResponse>("/products", queryParams = mapOf("page" to "1", "size" to "10")) { products ->
      products.size shouldBe 10
      products.first().name shouldNotBe null
    }
  }
}
```

### GET with Full Response Access

When you need access to status code and headers:

```kotlin
stove {
  http {
    getResponse<UserResponse>("/users/123") { response ->
      response.status shouldBe 200
      response.headers["Content-Type"] shouldContain "application/json"
      response.body().id shouldBe 123
    }

    // Bodiless response (only status and headers)
    getResponse("/health") { response ->
      response.status shouldBe 200
    }
  }
}
```

### POST Requests

Various POST request patterns:

```kotlin
stove {
  http {
    // POST with request body and expect JSON response
    postAndExpectJson<UserResponse>("/users") {
      CreateUserRequest(name = "John", email = "john@example.com")
    } { user ->
      user.id shouldNotBe null
      user.name shouldBe "John"
    }

    // POST and expect bodiless response (only status)
    postAndExpectBodilessResponse(
      uri = "/products/activate",
      body = ActivateRequest(productId = 123).some()
    ) { response ->
      response.status shouldBe 200
    }

    // POST with full response access
    postAndExpectBody<ProductResponse>(
      uri = "/products",
      body = CreateProductRequest(name = "Laptop", price = 999.99).some()
    ) { response ->
      response.status shouldBe 201
      response.headers["Location"] shouldNotBe null
      response.body().id shouldNotBe null
    }

    // POST with headers and token
    postAndExpectJson<OrderResponse>(
      uri = "/orders",
      body = CreateOrderRequest(items = listOf("item1", "item2")).some(),
      headers = mapOf("X-Request-ID" to "12345"),
      token = "jwt-token".some()
    ) { order ->
      order.id shouldNotBe null
      order.status shouldBe "CREATED"
    }
  }
}
```

### PUT Requests

Update operations with PUT:

```kotlin
stove {
  http {
    // PUT with response body
    putAndExpectJson<UserResponse>("/users/123") {
      UpdateUserRequest(name = "Jane Doe", email = "jane@example.com")
    } { user ->
      user.name shouldBe "Jane Doe"
      user.email shouldBe "jane@example.com"
    }

    // PUT without response body
    putAndExpectBodilessResponse(
      uri = "/products/123",
      body = UpdateProductRequest(name = "Updated Product").some()
    ) { response ->
      response.status shouldBe 200
    }

    // PUT with full response access
    putAndExpectBody<ProductResponse>(
      uri = "/products/456",
      body = UpdateProductRequest(price = 899.99).some()
    ) { response ->
      response.status shouldBe 200
      response.body().price shouldBe 899.99
    }
  }
}
```

### PATCH Requests

Partial updates with PATCH:

```kotlin
stove {
  http {
    // PATCH with response body
    patchAndExpectBody<UserResponse>(
      uri = "/users/123",
      body = mapOf("email" to "newemail@example.com").some()
    ) { response ->
      response.status shouldBe 200
      response.body().email shouldBe "newemail@example.com"
    }
  }
}
```

### DELETE Requests

Delete operations:

```kotlin
stove {
  http {
    // DELETE without response body
    deleteAndExpectBodilessResponse("/users/123") { response ->
      response.status shouldBe 204
    }

    // DELETE with authentication
    deleteAndExpectBodilessResponse(
      uri = "/products/456",
      token = "jwt-token".some()
    ) { response ->
      response.status shouldBe 200
    }
  }
}
```

### File Upload with Multipart

Upload files using multipart form data:

```kotlin
stove {
  http {
    postMultipartAndExpectResponse<UploadResponse>(
      uri = "/products/import",
      body = listOf(
        StoveMultiPartContent.Text("productName", "Laptop"),
        StoveMultiPartContent.Text("description", "A powerful laptop"),
        StoveMultiPartContent.File(
          param = "file",
          fileName = "products.csv",
          content = csvBytes,
          contentType = MediaType.APPLICATION_OCTET_STREAM_VALUE
        )
      )
    ) { response ->
      response.status shouldBe 200
      response.body().uploadedFiles.size shouldBe 1
      response.body().message shouldContain "products.csv"
    }
  }
}
```

### Advanced: Using Ktor Client Directly

For advanced scenarios, access the underlying Ktor HttpClient:

```kotlin
stove {
  http {
    client { baseUrl ->
      // Direct access to Ktor HttpClient
      val response = get {
        url(baseUrl.buildString() + "/custom-endpoint")
        header("Custom-Header", "value")
      }
      println(response.status)
    }
  }
}
```

## Complete Example

Here's a <span data-rn="underline" data-rn-color="#009688">complete CRUD test example</span>:

```kotlin hl_lines="7 20 30 38"
test("should perform CRUD operations on products") {
  stove {
    var productId: Long? = null

    // CREATE
    http {
      postAndExpectBody<ProductResponse>(
        uri = "/products",
        body = CreateProductRequest(name = "Laptop", price = 999.99, categoryId = 1).some()
      ) { response ->
        response.status shouldBe 201
        productId = response.body().id
        response.body().name shouldBe "Laptop"
      }
    }

    // READ
    http {
      get<ProductResponse>("/products/$productId") { product ->
        product.id shouldBe productId
        product.name shouldBe "Laptop"
        product.price shouldBe 999.99
      }
    }

    // UPDATE
    http {
      putAndExpectJson<ProductResponse>("/products/$productId") {
        UpdateProductRequest(price = 899.99)
      } { product ->
        product.price shouldBe 899.99
      }
    }

    // DELETE
    http {
      deleteAndExpectBodilessResponse("/products/$productId") { response ->
        response.status shouldBe 204
      }
    }

    // Verify deletion
    http {
      getResponse<ErrorResponse>("/products/$productId") { response ->
        response.status shouldBe 404
      }
    }
  }
}
```

## Integration with Other Components

### HTTP + Database

```kotlin hl_lines="4 12"
stove {
  // Create via API and capture user ID
  var userId: Long = 0
  http {
    postAndExpectBody<UserResponse>("/users", body = CreateUserRequest(name = "John").some()) { response ->
      userId = response.body().id
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

### HTTP + Kafka

```kotlin
stove {
  // Trigger event via API
  http {
    postAndExpectBodilessResponse("/orders", body = CreateOrderRequest(amount = 100.0).some()) { response ->
      response.status shouldBe 201
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

### HTTP + WireMock

```kotlin
stove {
  // Mock external service
  wiremock {
    mockGet(
      url = "/external-api/data",
      statusCode = 200,
      responseBody = ExternalData(id = 1, value = "test").some()
    )
  }

  // Call your API that depends on external service
  http {
    get<ResponseData>("/data") { response ->
      response.value shouldBe "test"
    }
  }
}
```

## Error Handling

```kotlin
stove {
  http {
    // Test validation errors
    postAndExpectBody<ValidationErrorResponse>("/users", body = InvalidUserRequest().some()) { response ->
      response.status shouldBe 400
      response.body().errors shouldContain "name is required"
    }

    // Test authentication errors
    getResponse<ErrorResponse>("/secure-endpoint") { response ->
      response.status shouldBe 401
    }

    // Test not found
    getResponse<ErrorResponse>("/users/999999") { response ->
      response.status shouldBe 404
    }

    // Test business logic errors
    postAndExpectBody<ErrorResponse>("/products", body = InvalidProductRequest().some()) { response ->
      response.status shouldBe 409 // Conflict
      response.body().message shouldContain "already exists"
    }
  }
}
```

## WebSocket Support

Stove provides <span data-rn="highlight" data-rn-color="#00968855" data-rn-duration="800">built-in support for testing WebSocket endpoints</span>. The WebSocket functionality is integrated into the HTTP system and uses Ktor's WebSocket client under the hood.

### Basic WebSocket Usage

Send and receive messages through a WebSocket connection:

```kotlin hl_lines="3 5 8"
stove {
  http {
    webSocket("/chat") {
      // Send a text message
      send("Hello, WebSocket!")
      
      // Receive a text message
      val response = receiveText()
      response shouldBe "Echo: Hello, WebSocket!"
    }
  }
}
```

### Sending Messages

Multiple ways to send messages:

```kotlin
stove {
  http {
    webSocket("/endpoint") {
      // Send text message
      send("Hello")
      
      // Send binary data
      send(byteArrayOf(1, 2, 3, 4, 5))
      
      // Send using sealed class
      send(StoveWebSocketMessage.Text("Hello via sealed class"))
      send(StoveWebSocketMessage.Binary(byteArrayOf(1, 2, 3)))
    }
  }
}
```

### Receiving Messages

Various methods to receive messages:

```kotlin
stove {
  http {
    webSocket("/endpoint") {
      // Receive text
      val text = receiveText()
      text shouldBe "expected message"
      
      // Receive binary
      val bytes = receiveBinary()
      bytes shouldBe byteArrayOf(1, 2, 3)
      
      // Receive as sealed class (auto-detect type)
      val message = receive()
      when (message) {
        is StoveWebSocketMessage.Text -> println(message.content)
        is StoveWebSocketMessage.Binary -> println(message.content.size)
        null -> println("Connection closed")
      }
      
      // Receive with timeout
      val response = receiveTextWithTimeout(5.seconds)
      response.isSome() shouldBe true
      response.getOrNull() shouldBe "expected"
    }
  }
}
```

### Collecting Multiple Messages

Collect a batch of messages:

```kotlin
stove {
  http {
    webSocket("/broadcast") {
      // Collect 5 text messages with a 10 second timeout
      val messages = collectTexts(count = 5, timeout = 10.seconds)
      messages.size shouldBe 5
      messages[0] shouldBe "Message 1"
      messages[4] shouldBe "Message 5"
      
      // Collect binary messages
      val binaryMessages = collectBinaries(count = 3, timeout = 5.seconds)
      binaryMessages.size shouldBe 3
    }
  }
}
```

### Streaming with Flow

Use Kotlin Flow for streaming scenarios:

```kotlin
stove {
  http {
    webSocket("/events") {
      // Stream text messages
      val messages = incomingTexts()
        .take(10)
        .toList()
      
      messages.size shouldBe 10
      
      // Stream binary messages
      incomingBinaries()
        .take(5)
        .collect { bytes ->
          println("Received ${bytes.size} bytes")
        }
      
      // Stream all message types
      incoming()
        .take(5)
        .collect { message ->
          when (message) {
            is StoveWebSocketMessage.Text -> println(message.content)
            is StoveWebSocketMessage.Binary -> println(message.content.size)
          }
        }
    }
  }
}
```

### Authentication and Headers

Connect with authentication or custom headers:

```kotlin
stove {
  http {
    // With bearer token
    webSocket(
      uri = "/secure-chat",
      token = "jwt-token".some()
    ) {
      val response = receiveText()
      response shouldBe "Authenticated successfully"
    }
    
    // With custom headers
    webSocket(
      uri = "/chat",
      headers = mapOf(
        "X-Custom-Header" to "value",
        "Authorization" to "Bearer custom-token"
      )
    ) {
      send("Hello with custom headers")
      receiveText() shouldNotBe null
    }
  }
}
```

### WebSocket Expect (Assertion Alias)

Use `webSocketExpect` for assertion-focused tests:

```kotlin
stove {
  http {
    webSocketExpect("/notifications") {
      val messages = collectTexts(count = 3)
      messages.size shouldBe 3
      messages.all { it.startsWith("notification:") } shouldBe true
    }
  }
}
```

### Raw WebSocket Access

For advanced scenarios, access the underlying Ktor WebSocket session:

```kotlin
stove {
  http {
    webSocketRaw("/advanced") {
      // Direct access to Ktor's DefaultClientWebSocketSession
      send(Frame.Text("raw frame"))
      
      for (frame in incoming) {
        when (frame) {
          is Frame.Text -> println(frame.readText())
          is Frame.Binary -> println(frame.readBytes().size)
          is Frame.Close -> break
          else -> {}
        }
      }
    }
  }
}
```

### Underlying Session Access

Access the underlying session from within `StoveWebSocketSession`:

```kotlin
stove {
  http {
    webSocket("/endpoint") {
      // Use simplified API first
      send("Hello")
      
      // Then access underlying session for advanced operations
      underlyingSession {
        send(Frame.Text("Advanced operation"))
        val frame = incoming.receive()
        (frame as Frame.Text).readText() shouldBe "Response"
      }
    }
  }
}
```

### Closing Connections

Gracefully close WebSocket connections:

```kotlin
stove {
  http {
    webSocket("/chat") {
      send("Hello")
      receiveText()
      
      // Close with custom reason
      close("Test completed")
    }
  }
}
```

### Complete WebSocket Test Example

A comprehensive example testing a chat application:

```kotlin
test("should handle chat room operations") {
  stove {
    http {
      // Test echo functionality
      webSocket("/chat/echo") {
        send("Hello, World!")
        receiveText() shouldBe "Echo: Hello, World!"
        
        send("Another message")
        receiveText() shouldBe "Echo: Another message"
      }
      
      // Test broadcast with authentication
      webSocket(
        uri = "/chat/room/123",
        token = "user-jwt-token".some()
      ) {
        // Verify join notification
        val joinMessage = receiveText()
        joinMessage shouldContain "joined"
        
        // Send a message
        send("Hi everyone!")
        
        // Collect broadcast responses
        val messages = collectTexts(count = 2, timeout = 5.seconds)
        messages.any { it.contains("Hi everyone!") } shouldBe true
      }
      
      // Test binary data (e.g., file sharing)
      webSocket("/chat/files") {
        val fileData = "Hello".toByteArray()
        send(fileData)
        
        val response = receiveBinary()
        response shouldNotBe null
      }
    }
  }
}
```

### WebSocket + Kafka Integration

Test WebSocket events that trigger Kafka messages:

```kotlin
stove {
  http {
    webSocket("/events") {
      send("""{"type": "order", "action": "create", "amount": 100.0}""")
      
      val confirmation = receiveText()
      confirmation shouldContain "received"
    }
  }
  
  kafka {
    shouldBePublished<OrderCreatedEvent>(atLeastIn = 10.seconds) {
      actual.amount == 100.0
    }
  }
}
```
