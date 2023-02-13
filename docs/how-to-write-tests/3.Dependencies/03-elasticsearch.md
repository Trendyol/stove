# Elasticsearch

## Prerequisites

### 1. Docker Image

```shell
 docker buildx imagetools create docker.elastic.co/elasticsearch/elasticsearch:8.6.1 --tag YOUR_REGISTRY/elasticsearch/elasticsearch:8.6.1
```

### 2. Library 

=== "Gradle"

    ``` kotlin
        dependencies {
            testImplementation("com.trendyol:stove-testing-e2e-elasticsearch:$version")
        }
    ```

=== "Maven"

    ```xml
     <dependency>
        <groupId>com.trendyol</groupId>
        <artifactId>stove-testing-e2e-elasticsearch</artifactId>
        <version>${stove-version}</version>
     </dependency>
    ```

## Configure
