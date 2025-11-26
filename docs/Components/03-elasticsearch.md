# Elasticsearch

=== "Gradle"

    ``` kotlin
        dependencies {
            testImplementation("com.trendyol:stove-testing-e2e-elasticsearch:$version")
        }
    ```

## Configure

After getting the library from the maven source, while configuring TestSystem you will have access to `elasticsearch`
function. This function configures the Elasticsearch Docker container that is going to be started.

```kotlin
TestSystem()
  .with {
    elasticsearch {
      ElasticsearchSystemOptions(configureExposedConfiguration = { cfg ->
        listOf(
          "elasticsearch.host=${cfg.host}",
          "elasticsearch.port=${cfg.port}",
          "elasticsearch.password=${cfg.password}"
        )
      })
    }
  }
  .run()
```

### Container Options

You can customize the Elasticsearch container:

```kotlin
TestSystem()
  .with {
    elasticsearch {
      ElasticsearchSystemOptions(
        container = ElasticContainerOptions(
          registry = "docker.elastic.co/",
          image = "elasticsearch/elasticsearch",
          tag = "8.6.1",
          password = "password",
          disableSecurity = true, // Disable for simpler test setup
          exposedPorts = listOf(9200)
        ),
        configureExposedConfiguration = { cfg ->
          listOf(
            "elasticsearch.host=${cfg.host}",
            "elasticsearch.port=${cfg.port}"
          )
        }
      )
    }
  }
  .run()
```

### Security Configuration

For secure Elasticsearch setups with authentication:

```kotlin
TestSystem()
  .with {
    elasticsearch {
      ElasticsearchSystemOptions(
        container = ElasticContainerOptions(
          disableSecurity = false, // Enable security
          password = "your-secure-password"
        ),
        configureExposedConfiguration = { cfg ->
          listOf(
            "elasticsearch.host=${cfg.host}",
            "elasticsearch.port=${cfg.port}",
            "elasticsearch.password=${cfg.password}",
            "elasticsearch.ssl.enabled=true"
          )
        }
      )
    }
  }
  .run()
```

### Client Configurer

Customize the Elasticsearch REST client:

```kotlin
TestSystem()
  .with {
    elasticsearch {
      ElasticsearchSystemOptions(
        clientConfigurer = ElasticClientConfigurer(
          httpClientBuilder = {
            setDefaultRequestConfig(
              RequestConfig.custom()
                .setSocketTimeout(60000)
                .setConnectTimeout(30000)
                .build()
            )
          }
        ),
        configureExposedConfiguration = { cfg ->
          listOf(
            "elasticsearch.host=${cfg.host}",
            "elasticsearch.port=${cfg.port}"
          )
        }
      )
    }
  }
  .run()
```

### Custom JSON Mapper

Use a custom Jackson ObjectMapper for serialization:

```kotlin
TestSystem()
  .with {
    elasticsearch {
      val customMapper = ObjectMapper().apply {
        registerModule(JavaTimeModule())
        disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
      }
      ElasticsearchSystemOptions(
        jsonpMapper = JacksonJsonpMapper(customMapper),
        configureExposedConfiguration = { cfg ->
          listOf(
            "elasticsearch.host=${cfg.host}",
            "elasticsearch.port=${cfg.port}"
          )
        }
      )
    }
  }
  .run()
```

## Migrations

Stove provides a way to run index migrations before tests start:

```kotlin
class CreateProductIndex : DatabaseMigration<ElasticsearchClient> {
  override val order: Int = 1

  override suspend fun execute(connection: ElasticsearchClient) {
    connection.indices().create { c ->
      c.index("products")
        .mappings { m ->
          m.properties("name") { p -> p.text { t -> t } }
            .properties("price") { p -> p.double_ { d -> d } }
            .properties("category") { p -> p.keyword { k -> k } }
            .properties("createdAt") { p -> p.date { d -> d } }
        }
    }
  }
}
```

Register migrations in your TestSystem configuration:

```kotlin
TestSystem()
  .with {
    elasticsearch {
      ElasticsearchSystemOptions(
        configureExposedConfiguration = { cfg ->
          listOf(
            "elasticsearch.host=${cfg.host}",
            "elasticsearch.port=${cfg.port}"
          )
        }
      ).migrations {
        register<CreateProductIndex>()
      }
    }
  }
  .run()
```

## Usage

### Saving Documents

Save documents to Elasticsearch indices:

```kotlin
TestSystem.validate {
  elasticsearch {
    // Save a document
    save(
      id = "product-123",
      instance = Product(
        id = "123",
        name = "Laptop",
        price = 999.99,
        category = "Electronics"
      ),
      index = "products"
    )
  }
}
```

### Getting Documents

Retrieve and validate documents:

```kotlin
TestSystem.validate {
  elasticsearch {
    // Get by ID and validate
    shouldGet<Product>(index = "products", key = "product-123") { product ->
      product.id shouldBe "123"
      product.name shouldBe "Laptop"
      product.price shouldBe 999.99
      product.category shouldBe "Electronics"
    }
  }
}
```

### Checking Non-Existence

Verify that documents don't exist:

```kotlin
TestSystem.validate {
  elasticsearch {
    // Verify document doesn't exist
    shouldNotExist(key = "product-999", index = "products")
  }
}
```

### Deleting Documents

Delete documents and verify deletion:

```kotlin
TestSystem.validate {
  elasticsearch {
    // Delete a document
    shouldDelete(key = "product-123", index = "products")
    
    // Verify deletion
    shouldNotExist(key = "product-123", index = "products")
  }
}
```

### Querying with JSON Query DSL

Execute Elasticsearch queries using JSON DSL:

```kotlin
TestSystem.validate {
  elasticsearch {
    // Query using JSON DSL
    shouldQuery<Product>(
      query = """
        {
          "bool": {
            "must": [
              { "match": { "category": "Electronics" } },
              { "range": { "price": { "gte": 500 } } }
            ]
          }
        }
      """.trimIndent(),
      index = "products"
    ) { products ->
      products.size shouldBeGreaterThan 0
      products.all { it.category == "Electronics" && it.price >= 500 } shouldBe true
    }
  }
}
```

### Querying with Query Builder

Use the Elasticsearch Java client's query builder:

```kotlin
TestSystem.validate {
  elasticsearch {
    // Query using Query builder
    val query = Query.of { q ->
      q.bool { b ->
        b.must { m ->
          m.match { t -> t.field("category").query("Electronics") }
        }.filter { f ->
          f.range { r -> r.field("price").gte(JsonData.of(500)) }
        }
      }
    }

    shouldQuery<Product>(query) { products ->
      products.size shouldBeGreaterThan 0
      products.all { it.category == "Electronics" && it.price >= 500 } shouldBe true
    }
  }
}
```

### Accessing the Client Directly

For advanced operations, access the Elasticsearch client:

```kotlin
TestSystem.validate {
  elasticsearch {
    val esClient = client()
    
    // Perform custom operations
    val indexExists = esClient.indices().exists { e -> e.index("products") }.value()
    indexExists shouldBe true
    
    // Bulk operations
    esClient.bulk { b ->
      b.operations { op ->
        op.index { i ->
          i.index("products")
            .id("bulk-1")
            .document(Product(id = "bulk-1", name = "Mouse", price = 29.99, category = "Electronics"))
        }
      }.operations { op ->
        op.index { i ->
          i.index("products")
            .id("bulk-2")
            .document(Product(id = "bulk-2", name = "Keyboard", price = 79.99, category = "Electronics"))
        }
      }
    }
  }
}
```

### Pause and Unpause Container

Control the Elasticsearch container for testing failure scenarios:

```kotlin
TestSystem.validate {
  elasticsearch {
    // Elasticsearch is running
    shouldGet<Product>(index = "products", key = "product-123") { product ->
      product.id shouldBe "123"
    }
    
    // Pause the container
    pause()
    
    // Your application should handle the failure
    // ...
    
    // Unpause the container
    unpause()
    
    // Verify recovery
    shouldGet<Product>(index = "products", key = "product-123") { product ->
      product.id shouldBe "123"
    }
  }
}
```

!!! warning
    `pause()` and `unpause()` operations are not supported when using a provided instance.

## Complete Example

Here's a complete end-to-end test combining HTTP, Elasticsearch, and Kafka:

```kotlin
test("should create product and index in elasticsearch") {
  TestSystem.validate {
    val productId = UUID.randomUUID().toString()
    val productName = "Gaming Laptop"
    val categoryId = 1

    // Mock external service
    wiremock {
      mockGet(
        url = "/categories/$categoryId",
        statusCode = 200,
        responseBody = Category(id = categoryId, name = "Electronics", active = true).some()
      )
    }

    // Create product via API
    http {
      postAndExpectBody<ProductResponse>(
        uri = "/products",
        body = ProductCreateRequest(
          name = productName,
          price = 1299.99,
          categoryId = categoryId
        ).some()
      ) { response ->
        response.status shouldBe 201
        response.body().id shouldNotBe null
      }
    }

    // Verify indexed in Elasticsearch
    elasticsearch {
      shouldGet<Product>(index = "products", key = productId) { product ->
        product.id shouldBe productId
        product.name shouldBe productName
        product.price shouldBe 1299.99
      }
    }

    // Verify event was published
    kafka {
      shouldBePublished<ProductCreatedEvent>(atLeastIn = 10.seconds) {
        actual.id == productId &&
        actual.name == productName
      }
    }

    // Query products by category
    elasticsearch {
      shouldQuery<Product>(
        query = """
          {
            "term": { "category": "Electronics" }
          }
        """.trimIndent(),
        index = "products"
      ) { products ->
        products.size shouldBeGreaterThan 0
        products.any { it.id == productId } shouldBe true
      }
    }
  }
}
```

## Integration with Application

Verify application behavior using the bridge:

```kotlin
test("should use service to index product") {
  TestSystem.validate {
    val productId = UUID.randomUUID().toString()
    val product = Product(id = productId, name = "Test Product", price = 99.99, category = "Test")

    // Use application's service
    using<ProductIndexingService> {
      indexProduct(product)
    }

    // Verify in Elasticsearch
    elasticsearch {
      shouldGet<Product>(index = "products", key = productId) { indexed ->
        indexed.id shouldBe productId
        indexed.name shouldBe "Test Product"
        indexed.price shouldBe 99.99
      }
    }
  }
}
```

## Advanced Operations

### Full-Text Search

```kotlin
TestSystem.validate {
  elasticsearch {
    // Setup test data
    listOf(
      Product(id = "1", name = "MacBook Pro 16 inch", price = 2499.99, category = "Laptops"),
      Product(id = "2", name = "MacBook Air M2", price = 1199.99, category = "Laptops"),
      Product(id = "3", name = "Dell XPS 15", price = 1799.99, category = "Laptops")
    ).forEach { product ->
      save(id = product.id, instance = product, index = "products")
    }

    // Full-text search
    shouldQuery<Product>(
      query = """
        {
          "multi_match": {
            "query": "MacBook",
            "fields": ["name", "description"]
          }
        }
      """.trimIndent(),
      index = "products"
    ) { results ->
      results.size shouldBe 2
      results.all { "MacBook" in it.name } shouldBe true
    }
  }
}
```

### Aggregations

```kotlin
TestSystem.validate {
  elasticsearch {
    val esClient = client()
    
    // Search with aggregations
    val response = esClient.search({ s ->
      s.index("products")
        .size(0)
        .aggregations("price_stats") { a ->
          a.stats { st -> st.field("price") }
        }
        .aggregations("by_category") { a ->
          a.terms { t -> t.field("category.keyword") }
        }
    }, Product::class.java)

    // Access aggregation results
    val priceStats = response.aggregations()["price_stats"]?.stats()
    priceStats?.avg() shouldNotBe null
    priceStats?.min() shouldNotBe null
    priceStats?.max() shouldNotBe null

    val categoryBuckets = response.aggregations()["by_category"]?.sterms()?.buckets()?.array()
    categoryBuckets?.size shouldBeGreaterThan 0
  }
}
```

### Index Management

```kotlin
TestSystem.validate {
  elasticsearch {
    val esClient = client()
    
    // Create index with custom settings
    esClient.indices().create { c ->
      c.index("test-index")
        .settings { s ->
          s.numberOfShards("1")
            .numberOfReplicas("0")
        }
        .mappings { m ->
          m.properties("title") { p -> p.text { t -> t.analyzer("standard") } }
            .properties("tags") { p -> p.keyword { k -> k } }
        }
    }
    
    // Check index exists
    val exists = esClient.indices().exists { e -> e.index("test-index") }.value()
    exists shouldBe true
    
    // Delete index
    esClient.indices().delete { d -> d.index("test-index") }
  }
}
```

## Provided Instance (External Elasticsearch)

For CI/CD pipelines or shared infrastructure:

```kotlin
TestSystem()
  .with {
    elasticsearch {
      ElasticsearchSystemOptions.provided(
        host = System.getenv("ELASTICSEARCH_HOST") ?: "localhost",
        port = System.getenv("ELASTICSEARCH_PORT")?.toInt() ?: 9200,
        password = System.getenv("ELASTICSEARCH_PASSWORD") ?: "",
        runMigrations = true,
        cleanup = { esClient ->
          // Clean up test indices after tests
          esClient.indices().delete { d -> d.index("test-*") }
        },
        configureExposedConfiguration = { cfg ->
          listOf(
            "elasticsearch.host=${cfg.host}",
            "elasticsearch.port=${cfg.port}",
            "elasticsearch.password=${cfg.password}"
          )
        }
      )
    }
  }
  .run()
```

## Data Classes Example

```kotlin
data class Product(
  val id: String,
  val name: String,
  val description: String? = null,
  val price: Double,
  val category: String,
  val tags: List<String> = emptyList(),
  val createdAt: Instant = Instant.now()
)

data class SearchResult(
  val total: Long,
  val products: List<Product>
)
```
