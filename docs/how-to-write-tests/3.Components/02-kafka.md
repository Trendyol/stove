# Kafka

## Prerequisites

### 1. Docker Image

```shell
 docker buildx imagetools create confluentinc/cp-kafka:latest --tag YOUR_REGISTRY/confluentinc/cp-kafka:latest  
```

### 2. Library

=== "Gradle"

    ``` kotlin
        dependencies {
            testImplementation("com.trendyol:stove-testing-e2e-kafka:$version")
        }
    ```

=== "Maven"

    ```xml
     <dependency>
        <groupId>com.trendyol</groupId>
        <artifactId>stove-testing-e2e-kafka</artifactId>
        <version>${stove-version}</version>
     </dependency>
    ```

## Configure

```kotlin
TestSystem(baseUrl = "http://localhost:8080")
    .with {
        // ... other deps ...  
        bridge()
        kafka {
            stoveKafkaObjectMapperRef = objectMapperRef
            KafkaSystemOptions {
                listOf(
                    "kafka.bootstrapServers=${it.bootstrapServers}",
                    "kafka.interceptorClasses=com.trendyol.stove.testing.e2e.standalone.kafka.intercepting.StoveKafkaBridge"
                )
            }
        }
    }.run()

```

### Configuring Object Mapper

Like every `SystemOptions` object, `KafkaSystemOptions` has a `stoveKafkaObjectMapperRef` field. You can set your own
object mapper to this field. If you don't set it, Stove will use its default object mapper.

```kotlin
var stoveKafkaObjectMapperRef: ObjectMapper = StoveObjectMapper.Default
```

### Kafka Bridge With Your Application

Stove Kafka bridge is a **MUST** to work with Kafka. Otherwise you can't assert any messages from your application.

As you can see in the example above, you need to add a support to your application to work with interceptor that Stove provides.

```kotlin
 "kafka.interceptorClasses=com.trendyol.stove.testing.e2e.standalone.kafka.intercepting.StoveKafkaBridge"
```

!!! Important
    `kafka.` prefix is an assumption that you can change it with your own prefix.

Make sure that `StoveKafkaBridge` is in your classpath.
