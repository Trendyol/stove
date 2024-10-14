# Ktor

[Here](https://github.com/Trendyol/stove/tree/main/examples/ktor-example) you can jump immediately to the Ktor example application.

## Deps

=== "Gradle"

    ```kotlin
    dependencies {
        testImplementation("com.trendyol:stove-ktor-testing-e2e:$version")
        
        // You can add other components if you need
        testImplementation("com.trendyol:stove-testing-e2e:$version")
        testImplementation("com.trendyol:stove-testing-e2e-kafka:$version")
        testImplementation("com.trendyol:stove-testing-e2e-mongodb:$version")
        testImplementation("com.trendyol:stove-testing-e2e-mssql:$version")
        testImplementation("com.trendyol:stove-testing-e2e-postgresql:$version")
        testImplementation("com.trendyol:stove-testing-e2e-redis:$version")
        testImplementation("com.trendyol:stove-testing-e2e-elasticsearch:$version")
        testImplementation("com.trendyol:stove-testing-e2e-couchbase:$version")
        testImplementation("com.trendyol:stove-testing-e2e-wiremock:$version")
        testImplementation("com.trendyol:stove-testing-e2e-http:$version")
    }
    ```

## Example Setup

```kotlin
TestSystem()
    .with {
        httpClient {
          HttpClientSystemOptions {
              baseUrl = "http://localhost:8080"
          }
        }
        bridge()
        postgresql {
            PostgresqlOptions(configureExposedConfiguration = { cfg ->
                listOf(
                    "database.jdbcUrl=${cfg.jdbcUrl}",
                    "database.host=${cfg.host}",
                    "database.port=${cfg.port}",
                    "database.name=${cfg.database}",
                    "database.username=${cfg.username}",
                    "database.password=${cfg.password}"
                )
            })
        }
        kafka {
            stoveKafkaObjectMapperRef = objectMapperRef
            KafkaSystemOptions {
                listOf(
                    "kafka.bootstrapServers=${it.bootstrapServers}"
                )
            }
        }
        wiremock {
            WireMockSystemOptions(
                port = 9090,
                removeStubAfterRequestMatched = true,
                afterRequest = { e, _ ->
                    logger.info(e.request.toString())
                }
            )
        }
        ktor(
            withParameters = listOf(
                "port=8080"
            ),
            runner = { parameters ->
                stove.ktor.example.run(parameters) {
                    addTestSystemDependencies()
                }
            }
        )
    }.run()
```
