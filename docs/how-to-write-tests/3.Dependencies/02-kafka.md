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
