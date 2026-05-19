# HTTP Client

Drive your app's REST API like a real client. Type-safe responses, full headers + status access, multipart uploads, WebSocket support.

<a class="open-in-wizard" data-sys="http">Open in setup wizard</a>

<!--{wizard:snippet id=sys.http parts=gradle,configure,test}-->

<div class="stove-tldr" markdown>
<span class="stove-tldr-title">In 30 seconds</span>
Add <code>stove-http</code>. Register <code>httpClient { HttpClientSystemOptions(baseUrl = "http://localhost:8080") }</code>. Drive endpoints with <code>get&lt;T&gt;</code>, <code>post&lt;T&gt;</code>, <code>put&lt;T&gt;</code>, <code>patch&lt;T&gt;</code>, <code>delete&lt;T&gt;</code>. Each returns parsed body via Jackson (or your converter). For full <code>status + headers + body</code>, use <code>*Response</code> / <code>*ExpectBody</code> variants.
</div>

## Configure

```kotlin
Stove().with {
  httpClient {
    HttpClientSystemOptions(baseUrl = "http://localhost:8080")
  }
}.run()
```

Options:

| Field | Default | Use |
|---|---|---|
| `baseUrl` | required | base for all relative URIs |
| `contentConverter` | `JacksonConverter(StoveSerde.jackson.default)` | use `GsonConverter`, or your own custom converter, or pass your app's `ObjectMapper` for alignment |
| `timeout` | `30.seconds` | HTTP request timeout |
| `createClient` | `jsonHttpClient(timeout, contentConverter)` | custom Ktor `HttpClient` factory |

## Test DSL by verb

### GET

```kotlin
stove {
  http {
    // body-only (typed)
    get<UserResponse>("/users/123") {
      it.id shouldBe 123
      it.name shouldBe "John Doe"
    }

    // query params
    get<String>("/api/search", queryParams = mapOf("q" to "stove")) {
      it shouldContain "stove"
    }

    // headers + token
    get<SecureData>(
      "/secure",
      headers = mapOf("X-Trace-Id" to "abc"),
      token = "jwt".some()
    ) {
      it.isAuthorized shouldBe true
    }

    // list response
    getMany<ProductResponse>("/products", queryParams = mapOf("page" to "1")) {
      it.size shouldBe 10
    }

    // full response (status + headers + body)
    getResponse<UserResponse>("/users/123") { response ->
      response.status shouldBe 200
      response.headers["Content-Type"] shouldContain "application/json"
      response.body().id shouldBe 123
    }

    // bodiless (just status)
    getResponse("/health") {
      it.status shouldBe 200
    }
  }
}
```

### POST / PUT / PATCH / DELETE

```kotlin
stove {
  http {
    // POST expecting JSON back
    postAndExpectJson<UserResponse>("/users") {
      CreateUserRequest(name = "John", email = "john@example.com")
    } { user ->
      user.id shouldNotBe null
    }

    // POST with full response
    postAndExpectBody<ProductResponse>(
      uri = "/products",
      body = CreateProductRequest(name = "Laptop", price = 999.99).some()
    ) { response ->
      response.status shouldBe 201
      response.headers["Location"] shouldNotBe null
    }

    // POST bodiless
    postAndExpectBodilessResponse(
      uri = "/products/activate",
      body = ActivateRequest(productId = 123).some()
    ) {
      it.status shouldBe 200
    }

    // PUT
    putAndExpectJson<UserResponse>("/users/123") {
      UpdateUserRequest(name = "Jane Doe")
    } { it.name shouldBe "Jane Doe" }

    // PATCH
    patchAndExpectBody<UserResponse>(
      uri = "/users/123",
      body = mapOf("email" to "new@example.com").some()
    ) { it.body().email shouldBe "new@example.com" }

    // DELETE
    deleteAndExpectBodilessResponse("/users/123") {
      it.status shouldBe 204
    }
  }
}
```

### Multipart upload

```kotlin
stove {
  http {
    postMultipartAndExpectResponse<UploadResponse>(
      uri = "/products/import",
      body = listOf(
        StoveMultiPartContent.Text("productName", "Laptop"),
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
    }
  }
}
```

### Escape hatch: raw Ktor client

```kotlin
stove {
  http {
    client { baseUrl ->
      val response = get {
        url(baseUrl.buildString() + "/custom")
        header("X-Custom", "value")
      }
      response.status.value shouldBe 200
    }
  }
}
```

## CRUD example

```kotlin hl_lines="7 20 28 36"
test("CRUD on products") {
  stove {
    var productId: Long? = null

    http {
      // create
      postAndExpectBody<ProductResponse>(
        uri = "/products",
        body = CreateProductRequest(name = "Laptop", price = 999.99).some()
      ) { response ->
        response.status shouldBe 201
        productId = response.body().id
      }

      // read
      get<ProductResponse>("/products/$productId") {
        it.name shouldBe "Laptop"
      }

      // update
      putAndExpectJson<ProductResponse>("/products/$productId") {
        UpdateProductRequest(price = 899.99)
      } { it.price shouldBe 899.99 }

      // delete
      deleteAndExpectBodilessResponse("/products/$productId") {
        it.status shouldBe 204
      }

      // verify gone
      getResponse<ErrorResponse>("/products/$productId") {
        it.status shouldBe 404
      }
    }
  }
}
```

## Error scenarios

```kotlin
stove {
  http {
    postAndExpectBody<ValidationErrorResponse>(
      uri = "/users",
      body = InvalidUserRequest().some()
    ) {
      it.status shouldBe 400
      it.body().errors shouldContain "name is required"
    }

    getResponse<ErrorResponse>("/secure") { it.status shouldBe 401 }
    getResponse<ErrorResponse>("/users/999999") { it.status shouldBe 404 }
  }
}
```

## WebSocket

`webSocket` opens a session inside `http { }`. Same connection lifetime as the lambda.

```kotlin
stove {
  http {
    webSocket("/chat") {
      send("Hello, WebSocket!")
      val response = receiveText()
      response shouldBe "Echo: Hello, WebSocket!"
    }
  }
}
```

### Send / receive

```kotlin
webSocket("/endpoint") {
  // send
  send("Hello")                                         // text
  send(byteArrayOf(1, 2, 3))                            // binary
  send(StoveWebSocketMessage.Text("via sealed class"))

  // receive
  val text = receiveText()
  val bytes = receiveBinary()
  val typed = receive()    // StoveWebSocketMessage.Text | Binary | null

  // with timeout
  val opt = receiveTextWithTimeout(5.seconds)           // Option<String>
}
```

### Batch + streaming

```kotlin
webSocket("/broadcast") {
  // batch with timeout
  val msgs = collectTexts(count = 5, timeout = 10.seconds)
  msgs.size shouldBe 5

  // streaming via Flow
  incomingTexts().take(10).toList() shouldHaveSize 10

  incoming().take(5).collect { msg ->
    when (msg) {
      is StoveWebSocketMessage.Text   -> println(msg.content)
      is StoveWebSocketMessage.Binary -> println(msg.content.size)
    }
  }
}
```

### Auth, headers, close, raw access

```kotlin
webSocket(uri = "/secure", token = "jwt".some()) { /* ... */ }

webSocket(
  uri = "/chat",
  headers = mapOf("X-Trace-Id" to "abc")
) {
  send("hi")
  close("test done")
}

// raw Ktor session for advanced frame control
webSocketRaw("/advanced") {
  send(Frame.Text("raw"))
  for (frame in incoming) {
    when (frame) {
      is Frame.Text -> println(frame.readText())
      is Frame.Close -> break
      else -> {}
    }
  }
}
```

## Multi-system patterns

### HTTP + DB

```kotlin
stove {
  var userId: Long = 0

  http {
    postAndExpectBody<UserResponse>(
      "/users",
      body = CreateUserRequest(name = "John").some()
    ) { userId = it.body().id }
  }

  postgresql {
    shouldQuery<User>(
      query = "SELECT * FROM users WHERE id = $userId",
      mapper = { row -> User(row.long("id"), row.string("name")) }
    ) { users ->
      users.first().name shouldBe "John"
    }
  }
}
```

### HTTP + Kafka

```kotlin
stove {
  http {
    postAndExpectBodilessResponse(
      "/orders",
      body = CreateOrderRequest(amount = 100.0).some()
    ) { it.status shouldBe 201 }
  }

  kafka {
    shouldBePublished<OrderCreatedEvent> {
      actual.amount == 100.0
    }
  }
}
```

### HTTP + WireMock

```kotlin
stove {
  wiremock {
    mockGet("/external/data", 200, ExternalData(value = "test").some())
  }

  http {
    get<ResponseData>("/data") { it.value shouldBe "test" }
  }
}
```

## Pairs well with

- [WireMock](04-wiremock.md). Mock outbound HTTP at the boundary
- [Recipes · order flow](../recipes/order-flow.md). Full multi-system flow
- [Bridge](10-bridge.md). Drive a domain service directly when there's no HTTP path
