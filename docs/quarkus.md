# Quarkus

`stove-quarkus` lets Stove start a Quarkus application in the same JVM as your test run, so reporting and `stove-tracing` continue to work with the normal Quarkus `main` entrypoint.

For a complete working project, see the [quarkus-example](https://github.com/Trendyol/stove/tree/main/examples/quarkus-example).

## What Is Supported

- Start Quarkus from your real `main` entrypoint with `quarkus { ... }`
- Use Stove components such as `stove-http`, `stove-postgres`, `stove-kafka`, and `stove-wiremock`
- Use `stove-tracing` and failure reporting in the same JVM
- Wait for readiness through HTTP or an explicit startup signal

!!! warning "Bridge support"
    `bridge()` is not available in `stove-quarkus` yet. Use HTTP, Kafka, database, gRPC, and tracing assertions to validate behavior from outside the Quarkus runtime.

## Dependencies

```kotlin
dependencies {
    testImplementation(platform("com.trendyol:stove-bom:$version"))

    testImplementation("com.trendyol:stove")
    testImplementation("com.trendyol:stove-quarkus")
    testImplementation("com.trendyol:stove-extensions-kotest")

    testImplementation("com.trendyol:stove-http")
    testImplementation("com.trendyol:stove-postgres")
    testImplementation("com.trendyol:stove-kafka")
    testImplementation("com.trendyol:stove-wiremock")
    testImplementation("com.trendyol:stove-tracing")
}
```

## Application Setup

Keep a normal Quarkus entrypoint and let Stove call it from tests:

```java
package com.example;

import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.annotations.QuarkusMain;

@QuarkusMain
public class QuarkusMainApp {

  public static void main(String[] args) {
    Quarkus.run(args);
  }
}
```

If your application does not expose an HTTP endpoint, publish an explicit startup signal so Stove can detect readiness:

```java
package com.example;

import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

@ApplicationScoped
public class StoveStartupSignal {

  void onStart(@Observes StartupEvent event) {
    System.setProperty("stove.quarkus.ready", "true");
  }

  void onStop(@Observes ShutdownEvent event) {
    System.clearProperty("stove.quarkus.ready");
  }
}
```

## Stove Configuration

```kotlin
import com.trendyol.stove.extensions.kotest.StoveKotestExtension
import com.trendyol.stove.http.HttpClientSystemOptions
import com.trendyol.stove.http.httpClient
import com.trendyol.stove.postgres.PostgresqlOptions
import com.trendyol.stove.postgres.postgresql
import com.trendyol.stove.quarkus.quarkus
import com.trendyol.stove.system.PortFinder
import com.trendyol.stove.system.Stove
import com.trendyol.stove.tracing.tracing
import io.kotest.core.config.AbstractProjectConfig
import io.kotest.core.extensions.Extension
import com.example.QuarkusMainApp

class TestConfig : AbstractProjectConfig() {
    private val appPort = PortFinder.findAvailablePort()

    override val extensions: List<Extension> = listOf(StoveKotestExtension())

    override suspend fun beforeProject() {
        Stove()
            .with {
                tracing {
                    enableSpanReceiver()
                }
                httpClient {
                    HttpClientSystemOptions(baseUrl = "http://localhost:$appPort")
                }
                postgresql {
                    PostgresqlOptions(
                        databaseName = "app",
                        configureExposedConfiguration = { cfg ->
                            listOf(
                                "quarkus.datasource.jdbc.url=${cfg.jdbcUrl}",
                                "quarkus.datasource.username=${cfg.username}",
                                "quarkus.datasource.password=${cfg.password}"
                            )
                        }
                    )
                }
                quarkus(
                    runner = { params -> QuarkusMainApp.main(params) },
                    withParameters = listOf("quarkus.http.port=$appPort")
                )
            }
            .run()
    }

    override suspend fun afterProject() {
        Stove.stop()
    }
}
```

## Kafka With Quarkus

If you use `stove-kafka` together with Quarkus Kafka clients, add this to `application.properties`:

```properties
quarkus.class-loading.parent-first-artifacts=org.apache.kafka:kafka-clients
```

This keeps the Kafka client classes shared so Stove's Kafka interceptor bridge can attach correctly.

If your application consumes from topics at startup, it is safer to create those topics before Quarkus boots. The [quarkus-example](https://github.com/Trendyol/stove/tree/main/examples/quarkus-example) does that through Stove Kafka migrations.

## Tracing

`stove-tracing` works with Quarkus because Stove launches the app in the same JVM as the test task. Add the tracing module, enable the span receiver in Stove, and configure the Gradle tracing plugin for the test task as usual.

## Example

The full example combines:

- Quarkus REST
- PostgreSQL + Flyway
- Kafka with `quarkus-messaging-kafka`
- WireMock for external HTTP dependencies
- Stove tracing and reporting

Reference:

- [examples/quarkus-example](https://github.com/Trendyol/stove/tree/main/examples/quarkus-example)
- [starters/quarkus/stove-quarkus](https://github.com/Trendyol/stove/tree/main/starters/quarkus/stove-quarkus)
