# Gradle Configuration

## Dependencies (BOM)

```kotlin
dependencies {
    testImplementation(platform("com.trendyol:stove-bom:$stoveVersion"))

    // Core
    testImplementation("com.trendyol:stove")

    // Application framework (pick one)
    testImplementation("com.trendyol:stove-spring")
    // testImplementation("com.trendyol:stove-ktor")

    // Test framework extension (pick one)
    testImplementation("com.trendyol:stove-extensions-kotest")  // Kotest 6.1.3+
    // testImplementation("com.trendyol:stove-extensions-junit") // JUnit Jupiter 6.x

    // Components — add only what you need
    testImplementation("com.trendyol:stove-http")
    testImplementation("com.trendyol:stove-postgres")
    testImplementation("com.trendyol:stove-kafka")          // Standalone Kafka
    // testImplementation("com.trendyol:stove-spring-kafka") // Spring Kafka (richer assertions)
    testImplementation("com.trendyol:stove-wiremock")
    testImplementation("com.trendyol:stove-grpc")            // gRPC client testing
    testImplementation("com.trendyol:stove-grpc-mock")       // gRPC mock server
    testImplementation("com.trendyol:stove-tracing")
}
```

## Register `test-e2e` source set

```kotlin
sourceSets {
    @Suppress("LocalVariableName")
    val `test-e2e` by creating {
        compileClasspath += sourceSets.main.get().output
        runtimeClasspath += sourceSets.main.get().output
    }

    val testE2eImplementation by configurations.getting {
        extendsFrom(configurations.testImplementation.get())
    }
    configurations["testE2eRuntimeOnly"].extendsFrom(configurations.runtimeOnly.get())
}
```

## Register `e2eTest` task

```kotlin
tasks.register<Test>("e2eTest") {
    description = "Runs e2e tests."
    group = "verification"
    testClassesDirs = sourceSets["test-e2e"].output.classesDirs
    classpath = sourceSets["test-e2e"].runtimeClasspath

    useJUnitPlatform()
    reports {
        junitXml.required.set(true)
        html.required.set(true)
    }
}
```

## IDE integration

```kotlin
idea {
    module {
        testSources.from(sourceSets["test-e2e"].allSource.sourceDirectories)
        testResources.from(sourceSets["test-e2e"].resources.sourceDirectories)
    }
}
```

## Available artifact names

| Artifact | Description |
|---|---|
| `stove` | Core framework |
| `stove-spring` | Spring Boot starter |
| `stove-ktor` | Ktor starter |
| `stove-http` | HTTP client system |
| `stove-postgres` | PostgreSQL system |
| `stove-kafka` | Standalone Kafka system |
| `stove-spring-kafka` | Spring Kafka system (richer assertions) |
| `stove-wiremock` | WireMock system |
| `stove-grpc` | gRPC client system |
| `stove-grpc-mock` | gRPC mock server system |
| `stove-tracing` | Tracing system |
| `stove-extensions-kotest` | Kotest integration (reporting) |
| `stove-extensions-junit` | JUnit integration (reporting) |
| `stove-couchbase` | Couchbase system |
| `stove-elasticsearch` | Elasticsearch system |
| `stove-redis` | Redis system |
| `stove-mongodb` | MongoDB system |
| `stove-mysql` | MySQL system |
| `stove-mssql` | MSSQL system |
