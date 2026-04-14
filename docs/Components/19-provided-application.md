# <span data-rn="underline" data-rn-color="#ff9800">Provided Application</span> (Black-Box Testing)

Stove normally starts the application under test locally via a framework starter (`springBoot()`, `ktor()`, etc.). With `providedApplication()`, you can skip that entirely and <span data-rn="highlight" data-rn-color="#00968855" data-rn-duration="800">test against a remote, already-deployed application</span> --- regardless of what language or framework it's built with.

## When to Use

- **Staging/pre-production validation** --- verify deployed services before release
- **Polyglot testing** --- the app can be Go, Python, .NET, Rust, Node.js, or anything else
- **Microservice integration** --- test a service through its public API and verify side effects in databases, Kafka, and caches
- **Smoke testing** --- run Stove tests as post-deployment checks in CI/CD

## Configure

`providedApplication()` replaces the framework starter (`springBoot()`, `ktor()`, etc.) in the `with` block. HTTP, databases, and other systems are configured separately as usual.

```kotlin hl_lines="3 8-13"
Stove().with {
    // Your app's API --- configured via httpClient as usual
    httpClient {
        HttpClientSystemOptions(baseUrl = "https://staging.myapp.com")
    }

    // Signal: app is already running, don't start it
    providedApplication {
        ProvidedApplicationOptions(
            healthCheck = HealthCheckOptions(
                url = "https://staging.myapp.com/actuator/health"
            )
        )
    }
}.run()
```

### Health Check

The optional health check verifies the remote application is reachable before tests run. If the check fails after all retries, Stove throws immediately with a clear error.

```kotlin
HealthCheckOptions(
    url = "https://staging.myapp.com/health",   // Health endpoint URL
    timeout = 30.seconds,                        // HTTP request timeout
    retries = 10,                                // Number of retry attempts
    retryDelay = 1.seconds,                      // Delay between retries
    expectedStatusCodes = setOf(200)              // Status codes considered healthy
)
```

### No Health Check

If you're sure the app is up, skip the health check entirely:

```kotlin
providedApplication()  // No-op --- just satisfies the AUT requirement
```

## Complete Example

```kotlin hl_lines="4 11-14 17-24 27"
class TestConfig : AbstractProjectConfig() {
    override suspend fun beforeProject() {
        Stove().with {
            // The app itself
            httpClient {
                HttpClientSystemOptions(baseUrl = "https://staging.myapp.com")
            }

            // Infrastructure the app connects to
            postgresql {
                PostgresqlOptions.provided(
                    jdbcUrl = "jdbc:postgresql://staging-db:5432/myapp",
                    host = "staging-db", port = 5432,
                    configureExposedConfiguration = { emptyList() }
                )
            }

            kafka {
                KafkaSystemOptions.provided(
                    bootstrapServers = "staging-kafka:9092",
                    configureExposedConfiguration = { emptyList() }
                )
            }

            // App is already deployed
            providedApplication {
                ProvidedApplicationOptions(
                    healthCheck = HealthCheckOptions(
                        url = "https://staging.myapp.com/actuator/health",
                        timeout = 15.seconds
                    )
                )
            }
        }.run()
    }

    override suspend fun afterProject() = Stove.stop()
}
```

## Writing Tests

Tests are written exactly the same way --- the DSL doesn't change:

```kotlin
test("create order and verify side effects") {
    stove {
        http {
            postAndExpectJson<OrderResponse>("/orders", body = request.some()) { order ->
                order.id shouldNotBe null
            }
        }

        postgresql {
            shouldQuery<Order>("SELECT * FROM orders WHERE id = ?", listOf(orderId)) { rows ->
                rows shouldHaveSize 1
            }
        }

        kafka {
            // Use consumer() to read directly from topics (no interceptor needed)
            consumer<String, OrderCreatedEvent>("orders.output", readOnly = true) { record ->
                record.value().orderId shouldBe orderId
            }
        }
    }
}
```

## Limitations

| Feature | Available? | Notes |
|---------|-----------|-------|
| HTTP/gRPC assertions | Yes | Via `httpClient {}` and `grpc {}` |
| Database queries | Yes | Via `postgresql {}`, `mongodb {}`, etc. |
| Kafka publish + consumer | Yes | `publish()` and `consumer()` work directly |
| Kafka `shouldBeConsumed` | No | Requires interceptor bridge inside the app |
| `using<T> {}` (Bridge) | No | Remote app's DI container is not accessible |

!!! warning "Bridge Not Supported"
    `using<MyService> { }` accesses the application's DI container, which is only possible when the app runs in the same JVM. With `providedApplication()`, calling `using<T>` throws a clear error explaining this.

## Suggested Source Set

For projects that have both local e2e tests and black-box tests against deployed apps:

```
my-service/
  src/
    main/           # Application code
    test/            # Unit tests
    test-e2e/        # Stove e2e tests (app started locally)
    test-blackbox/   # Stove black-box tests (providedApplication)
```

See also: [Multiple Systems](20-multiple-systems.md) for testing against multiple named service instances.
