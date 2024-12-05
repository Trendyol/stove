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

```kotlin
validate {
  http {
    get<YourResponse>("/relative-url") { actual ->
      actual shouldBe expected
    }
  }
}
```
