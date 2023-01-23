# Getting Started

## Prerequisites

- JDK 16+
- Kotlin 1.8
- Gradle 7.6 Kotlin Dsl (If you have `maven` that is also possible to implement but we advise to use `Gradle` build system, things are much simpler)

!!! warning
    Documentation assumes that your project using gradle build system

**Dependencies:**

Since these dependencies are test dependencies scope all of them with test. Here in gradle it can be with `testImplementation`.

`$version="0.0.7-SNAPSHOT" // always check the newest version, this might be outdated`

- `testImplementation("com.trendyol:stove-spring-testing-e2e:$version")`
- `testImplementation("com.trendyol:stove-spring-testing-e2e-http:$version")`
- `testImplementation("com.trendyol:stove-spring-testing-e2e-kafka:$version")`
- `testImplementation("com.trendyol:stove-spring-testing-e2e-couchbase:$version")`
- `testImplementation("com.trendyol:stove-spring-testing-e2e-wiremock:$version")`

- Spring-Boot 2.7.7 (all spring boot stack should be)
- Couchbase Java Client (`implementation("com.couchbase.client:java-client:3.4.1")`)
- Couchbase Client Metrics Micrometer (`implementation("com.couchbase.client:metrics-micrometer:0.4.1")`)
- kotlinx stack 1.6.4 `implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.6.4")`
