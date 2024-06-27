# 2. Ktor

[Here](https://github.com/Trendyol/stove/tree/main/examples/ktor-example) you can jump immediately to the Ktor example application.

## Deps

=== "Gradle"

    ```kotlin
    dependencies {
        testImplementation("com.trendyol:stove-ktor-testing-e2e:$version")
    }
    ```

=== "Maven"

    ```xml
     <dependency>
        <groupId>com.trendyol</groupId>
        <artifactId>stove-ktor-testing-e2e</artifactId>
        <version>${stove-version}</version>
     </dependency>
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
