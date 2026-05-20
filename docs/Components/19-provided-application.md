# Provided Application (Black-Box)

Skip the local AUT boot. Drive Stove tests against a **remote, already-deployed** application. The app can be written in any language; Stove treats it as an already-running black-box endpoint.

<div class="stove-tldr" markdown>
<span class="stove-tldr-title">In 30 seconds</span>
Replace your framework starter (<code>springBoot</code>, <code>ktor</code>, <code>goApp</code>) with <code>providedApplication { }</code> plus an optional readiness probe. Wire <code>httpClient</code> to the remote URL. Use <code>.provided(...)</code> on every system that points at staging infrastructure. Stove does not boot the app or inject configuration; verify through public endpoints and provided systems.
</div>

## When to use

- **Staging validation**. Verify deployed services before release
- **Polyglot testing**. The app can be Go, Python, .NET, Rust, Node, anything
- **Microservice integration**. Drive your service through its public API, verify side effects in shared DBs / Kafka / caches
- **Smoke testing**. Post-deployment checks in CI/CD

## Configure

`providedApplication { }` replaces the framework starter in `Stove().with`. HTTP and other systems are configured as usual, but all application configuration must already exist in the deployed environment.

```kotlin hl_lines="2 3 4 7 8 9 10 11 12 13"
Stove().with {
  httpClient {
    HttpClientSystemOptions(baseUrl = "https://staging.myapp.com")
  }

  providedApplication {
    ProvidedApplicationOptions(
      readiness = ReadinessStrategy.HttpGet(
        url = "https://staging.myapp.com/actuator/health"
      )
    )
  }
}.run()
```

### Readiness probe

Verifies the remote app is reachable before tests run. If checks fail after all retries, Stove throws with a clear error.

```kotlin
ReadinessStrategy.HttpGet(
  url = "https://staging.myapp.com/health",
  timeout = 30.seconds,
  retries = 10,
  retryDelay = 1.seconds,
  expectedStatusCodes = setOf(200)
)
```

Skip the probe entirely if you're sure the app is up:

```kotlin
providedApplication()  // no-op runner, satisfies AUT requirement
```

## Full example

```kotlin hl_lines="5 11 18 25"
class TestConfig : AbstractProjectConfig() {
  override suspend fun beforeProject() {
    Stove().with {
      httpClient {
        HttpClientSystemOptions(baseUrl = "https://staging.myapp.com")
      }

      postgresql {
        PostgresqlOptions.provided(
          jdbcUrl = "jdbc:postgresql://staging-db:5432/myapp",
          host = "staging-db",
          port = 5432,
          configureExposedConfiguration = { emptyList() }
        )
      }

      kafka {
        KafkaSystemOptions.provided(
          bootstrapServers = "staging-kafka:9092",
          configureExposedConfiguration = { emptyList() }
        )
      }

      providedApplication {
        ProvidedApplicationOptions(
          readiness = ReadinessStrategy.HttpGet(
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

## Writing tests

Use the same registered system DSL where the underlying system can be reached from the test process. DI bridge access through `using<T> { }` is unavailable for `providedApplication()`. Kafka observer assertions such as `shouldBePublished` and `shouldBeConsumed` only work if the remote app is separately configured to report to Stove; otherwise use direct consumers and system assertions.

```kotlin
test("create order, verify side effects on staging") {
  stove {
    http {
      postAndExpectJson<OrderResponse>("/orders", body = request.some()) { order ->
        order.id shouldNotBe null
      }
    }

    postgresql {
      shouldQuery<Order>(
        "SELECT * FROM orders WHERE id = ?",
        listOf(orderId)
      ) { rows ->
        rows shouldHaveSize 1
      }
    }

    kafka {
      // use consumer() to read directly. no interceptor inside the AUT
      consumer<String, OrderCreatedEvent>("orders.output", readOnly = true) { record ->
        record.value().orderId shouldBe orderId
      }
    }
  }
}
```

## What works, what doesn't

| Feature | Available? | Notes |
|---|---|---|
| HTTP / gRPC assertions | ✓ | `httpClient { }`, `grpc { }` |
| Database queries | ✓ | `postgresql { }`, `mongodb { }`, ... Via `.provided(...)` |
| Kafka `publish()` + `consumer()` | ✓ | Direct producer / consumer access |
| Kafka `shouldBeConsumed` | ✗ | Requires interceptor inside the AUT |
| `using<T> { }` (Bridge) | ✗ | Remote DI container inaccessible |

!!! warning "Bridge isn't supported"
    `using<MyService> { }` reaches into the AUT's DI container. That is only possible when the AUT runs in the same JVM. Provided applications get a clear error.

## Suggested source-set layout

```
my-service/
  src/
    main/            application code
    test/            unit tests
    test-e2e/        local Stove e2e (app boots in JVM)
    test-blackbox/   Stove smoke tests (providedApplication)
```

## Related

- [Provided Instances](11-provided-instances.md) for the `.provided(...)` patterns on each system
- [Multiple Systems](20-multiple-systems.md) for hitting multiple deployed services
- [Polyglot](../other-languages/index.md) for testing non-JVM apps
