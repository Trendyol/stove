# HttpClient

=== "Gradle"

    ``` kotlin
        dependencies {
            testImplementation("com.trendyol:stove-testing-e2e-http:$version")
        }
    ```

## Configure

After getting the library from the maven source, while configuring TestSystem you will have access to `http`

```kotlin
TestSystem()
  .with {
    http {
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

```kotlin
TestSystem.validate {
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
TestSystem.validate {
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
TestSystem.validate {
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
TestSystem.validate {
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
TestSystem.validate {
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
TestSystem.validate {
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
TestSystem.validate {
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
TestSystem.validate {
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

Here's a complete CRUD test example:

```kotlin
test("should perform CRUD operations on products") {
  TestSystem.validate {
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

```kotlin
TestSystem.validate {
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
TestSystem.validate {
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
TestSystem.validate {
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
TestSystem.validate {
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
