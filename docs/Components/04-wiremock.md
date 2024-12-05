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
