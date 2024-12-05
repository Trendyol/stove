# Elasticsearch

=== "Gradle"

    ``` kotlin
        dependencies {
            testImplementation("com.trendyol:stove-testing-e2e-elasticsearch:$version")
        }
    ```

## Configure

After getting the library from the maven source, while configuring TestSystem you will have access to `elasticsearch`
function.
This function configures the Elasticsearch Docker container that is going to be started.

```kotlin
TestSystem()
  .with {
    elasticsearch {
      ElasticsearchSystemOptions(configureExposedConfiguration = { cfg ->
        listOf(
          "elasticsearch.hosts=${cfg.hostsWithPort}",
          "elasticsearch.username=${cfg.username}",
          "elasticsearch.password=${cfg.password}"
        )
      })
    }
  }
  .run()
```
