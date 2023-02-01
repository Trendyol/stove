# Referencing Stove libraries in your project

After you figure out the physical dependencies you can add the library dependencies of Stove.

If you want to use Couchbase you should add Stove Couchbase e2e test dependency:

```kotlin
val version = "0.0.6-SNAPSHOT" // Check before setting it, there might be newer version
testImplementation("com.trendyol:stove-spring-testing-e2e:$version")
testImplementation("com.trendyol:stove-spring-testing-e2e-http:$version")
testImplementation("com.trendyol:stove-spring-testing-e2e-kafka:$version")
testImplementation("com.trendyol:stove-spring-testing-e2e-couchbase:$version")
testImplementation("com.trendyol:stove-spring-testing-e2e-wiremock:$version")
```
