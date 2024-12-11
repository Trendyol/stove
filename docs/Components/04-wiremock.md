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

```kotlin
wiremock {
  mockGet("/api/product/get-all", 200, lisOf(Product("1", "Product 1"), Product("2", "Product 2")).some())
}
```

Relative url is mocked. BaseUrl is known by Wiremock server since it hosts it, and your application because you passed
it as a command line argument.

### Behavioural Mocking

Sometimes, a service call returns a failure response before a success response. You can define this behaviour with
behavioural mocking.

```kotlin
test("behavioural tests") {
  val expectedGetDtoName = UUID.randomUUID().toString()
  TestSystem.validate {
    wiremock {
      behaviourFor("/get-behaviour", WireMock::get) {
        initially {
          aResponse()
            .withStatus(503)
            .withBody("Service unavailable")
        }
        then {
          aResponse()
            .withHeader("Content-Type", "application/json")
            .withStatus(200)
            .withBody(it.serialize(TestDto(expectedGetDtoName)))
        }
      }
    }
    http {
      this.getResponse("/get-behaviour") { actual ->
        actual.status shouldBe 503
      }
      get<TestDto>("/get-behaviour") { actual ->
        actual.name shouldBe expectedGetDtoName
      }
    }
  }
}
```

Here we define a behaviour for the `/get-behaviour` endpoint. Initially, it returns a 503 status code with a message.
Then, it returns a 200 status code with a `TestDto` object.
