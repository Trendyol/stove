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

## Configure

