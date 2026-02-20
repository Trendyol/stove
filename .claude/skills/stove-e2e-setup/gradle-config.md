# Gradle Configuration

## Contents
- [Dependencies (BOM)](#dependencies-bom)
- [Register test-e2e source set](#register-test-e2e-source-set)
- [Register e2eTest task](#register-e2etest-task)
- [IDE integration](#ide-integration)
- [JUnit base test class](#junit-base-test-class)
- [Available artifacts](#available-artifacts)

## Dependencies (BOM)

Stove e2e tests are Kotlin-first. Even for Java/Scala projects, keep e2e test sources in `src/test-e2e/kotlin`.

```kotlin
dependencies {
    testImplementation(platform("com.trendyol:stove-bom:$stoveVersion"))
    testImplementation("com.trendyol:stove")
    testImplementation("com.trendyol:stove-spring")             // or stove-ktor
    testImplementation("com.trendyol:stove-extensions-kotest")  // or stove-extensions-junit

    // Add only what you need:
    testImplementation("com.trendyol:stove-http")
    testImplementation("com.trendyol:stove-postgres")
    testImplementation("com.trendyol:stove-kafka")              // standalone Kafka assertions
    testImplementation("com.trendyol:stove-spring-kafka")       // Spring Kafka assertions + interceptor
    testImplementation("com.trendyol:stove-wiremock")
    testImplementation("com.trendyol:stove-grpc")
    testImplementation("com.trendyol:stove-grpc-mock")
    testImplementation("com.trendyol:stove-tracing")
}
```

## Register test-e2e source set

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

## Register e2eTest task

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

## Resolve API ambiguity from local artifacts

When API names/signatures are unclear, inspect locally downloaded Stove artifacts instead of guessing.

```bash
# Find Stove artifacts in Gradle cache
find ~/.gradle/caches/modules-2/files-2.1 -path "*com.trendyol/stove-*/*/*.jar" | head -n 20

# Find Stove artifacts in Maven local repo
find ~/.m2/repository/com/trendyol -name "stove-*.jar" | head -n 20

# List classes to locate exact type names
jar tf ~/.m2/repository/com/trendyol/stove-spring-kafka/<version>/stove-spring-kafka-<version>.jar | rg "TestSystem|Kafka"

# Inspect method signatures quickly
javap -classpath ~/.m2/repository/com/trendyol/stove-spring-kafka/<version>/stove-spring-kafka-<version>.jar \
  com.trendyol.stove.kafka.TestSystemKafkaInterceptor
```

Prefer `*-sources.jar` when available for more accurate reading of function names, generic types, and usage patterns.

## JUnit base test class

Use this instead of `AbstractProjectConfig` when using JUnit:

```kotlin
@ExtendWith(StoveJUnitExtension::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class BaseE2ETest {
    companion object {
        @JvmStatic @BeforeAll
        fun setup() = runBlocking {
            Stove().with { /* systems */ }.run()
        }

        @JvmStatic @AfterAll
        fun teardown() = runBlocking { Stove.stop() }
    }
}
```

## Available artifacts

| Artifact | Description |
|---|---|
| `stove` | Core framework |
| `stove-spring` | Spring Boot starter |
| `stove-ktor` | Ktor starter |
| `stove-http` | HTTP client system |
| `stove-postgres` | PostgreSQL system |
| `stove-kafka` | Standalone Kafka system |
| `stove-spring-kafka` | Spring Kafka (adds `shouldBeConsumed`, `shouldBeFailed`, `shouldBeRetried`) |
| `stove-wiremock` | WireMock system |
| `stove-grpc` | gRPC client system |
| `stove-grpc-mock` | gRPC mock server system |
| `stove-tracing` | Tracing system |
| `stove-extensions-kotest` | Kotest reporting integration |
| `stove-extensions-junit` | JUnit reporting integration |
| `stove-couchbase` | Couchbase system |
| `stove-elasticsearch` | Elasticsearch system |
| `stove-redis` | Redis system |
| `stove-mongodb` | MongoDB system |
| `stove-mysql` | MySQL system |
| `stove-mssql` | MSSQL system |
