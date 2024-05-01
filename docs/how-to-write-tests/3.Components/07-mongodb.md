# Mongodb

## Prerequisites

### 1. Docker Image

```shell
 docker buildx imagetools create mongo:latest --tag YOUR_REGISTRY/mongo:latest  
```

### 2. Library

=== "Gradle"

    ``` kotlin
        dependencies {
            testImplementation("com.trendyol:stove-testing-e2e-mongodb:$version")
        }
    ```

=== "Maven"

    ```xml
     <dependency>
        <groupId>com.trendyol</groupId>
        <artifactId>stove-testing-e2e-mongodb</artifactId>
        <version>${stove-version}</version>
     </dependency>
    ```

## Configure

