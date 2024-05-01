# Postgresql

## Prerequisites

### 1. Docker Image

```shell
 docker buildx imagetools create postgres:latest --tag YOUR_REGISTRY/postgres:latest  
```

### 2. Library

=== "Gradle"

    ``` kotlin
        dependencies {
            testImplementation("com.trendyol:stove-testing-e2e-rdbms-postgres:$version")
        }
    ```

=== "Maven"

    ```xml
     <dependency>
        <groupId>com.trendyol</groupId>
        <artifactId>stove-testing-e2e-rdbms-postgres</artifactId>
        <version>${stove-version}</version>
     </dependency>
    ```

## Configure

