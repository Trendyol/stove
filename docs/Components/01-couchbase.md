# Couchbase

=== "Gradle"

    ``` kotlin
        dependencies {
            testImplementation(platform("com.trendyol:stove-bom:$version"))
            testImplementation("com.trendyol:stove-couchbase")
        }
    ```

## Configure

Once you've added the dependency, you'll have access to the `couchbase` function when configuring Stove. This sets up the Couchbase Docker container that will be started for your tests.

You'll need to define a `defaultBucket` name. Make sure this matches what your application expects.

!!! warning
    Your application needs to use the same bucket names, otherwise tests will fail.

```kotlin hl_lines="4 5-9"
Stove()
  .with {
    couchbase {
      CouchbaseSystemOptions(defaultBucket = "test-bucket", configureExposedConfiguration = { cfg ->
        listOf(
          "couchbase.hosts=${cfg.hostsWithPort}",
          "couchbase.username=${cfg.username}",
          "couchbase.password=${cfg.password}"
        )
      })
    }
  }
  .run()
```

Stove exposes the configuration it generates, so you can pass the real connection strings and credentials to your application before it starts.
Your application will start with the physical dependencies that are spun-up by the framework.

## Migrations

Stove provides a way to run migrations before the test starts.

```kotlin
class CouchbaseMigration : DatabaseMigration<Cluster> {
  override val order: Int = 1

  override suspend fun execute(connection: Cluster) {
    val bucket = connection.bucket(CollectionConstants.BUCKET_NAME)
    listOf(CollectionConstants.PRODUCT_COLLECTION).forEach { collection ->
      bucket.collections.createCollection(bucket.defaultScope().name, collection)
    }
    connection.waitUntilReady(30.seconds)
  }
}
```

You can define your migration class by implementing the `DatabaseMigration` interface. You can define the order of the
migration by overriding the `order` property. The migrations will be executed in the order of the `order` property.

After defining your migration class, you can pass it to the `migrations` function of the `couchbase` configuration.

```kotlin
Stove()
  .with {
    couchbase {
      CouchbaseSystemOptions(defaultBucket = "test-bucket", configureExposedConfiguration = { cfg ->
        listOf(
          "couchbase.hosts=${cfg.hostsWithPort}",
          "couchbase.username=${cfg.username}",
          "couchbase.password=${cfg.password}"
        )
      }).migrations {
        register<CouchbaseMigration>()
      }
    }
  }
  .run()
```

## Usage

### Saving Documents

Save documents to Couchbase collections:

```kotlin
stove {
  couchbase {
    // Save to default collection (_default)
    saveToDefaultCollection(
      id = "user:123",
      instance = User(id = "123", name = "John Doe", email = "john@example.com")
    )

    // Save to a specific collection
    save(
      collection = "products",
      id = "product:456",
      instance = Product(id = "456", name = "Laptop", price = 999.99)
    )
  }
}
```

### Getting Documents

Retrieve and validate documents:

```kotlin hl_lines="4 11"
stove {
  couchbase {
    // Get from default collection
    shouldGet<User>("user:123") { user ->
      user.id shouldBe "123"
      user.name shouldBe "John Doe"
      user.email shouldBe "john@example.com"
    }

    // Get from specific collection
    shouldGet<Product>("products", "product:456") { product ->
      product.id shouldBe "456"
      product.name shouldBe "Laptop"
      product.price shouldBe 999.99
    }
  }
}
```

### Checking Non-Existence

Verify that documents don't exist:

```kotlin
stove {
  couchbase {
    // Check default collection
    shouldNotExist("user:999")

    // Check specific collection
    shouldNotExist("products", "product:999")
  }
}
```

### Deleting Documents

Delete documents and verify deletion:

```kotlin
stove {
  couchbase {
    // Delete from default collection
    shouldDelete("user:123")
    shouldNotExist("user:123")

    // Delete from specific collection
    shouldDelete("products", "product:456")
    shouldNotExist("products", "product:456")
  }
}
```

### N1QL Queries

Execute N1QL queries and validate results:

```kotlin hl_lines="4 11"
stove {
  couchbase {
    // Simple query
    shouldQuery<User>("SELECT u.* FROM `users` u WHERE u.age > 18") { users ->
      users.size shouldBeGreaterThan 0
      users.all { it.age > 18 } shouldBe true
    }

    // Query with multiple conditions
    shouldQuery<Product>(
      """
      SELECT p.* 
      FROM `products` p 
      WHERE p.price > 100 AND p.category = 'Electronics'
      """.trimIndent()
    ) { products ->
      products.size shouldBeGreaterThan 0
      products.all { it.price > 100 && it.category == "Electronics" } shouldBe true
    }
  }
}
```

### Working with Collections and Scopes

Access bucket, collection, and cluster directly:

```kotlin
stove {
  couchbase {
    // Access the cluster
    val cluster = cluster()
    
    // Access the bucket
    val bucket = bucket()
    
    // Perform custom operations
    val customResult = bucket.collections.getAllScopes()
    customResult shouldNotBe null
  }
}
```

### Pause and Unpause Container

Control the Couchbase container for testing failure scenarios:

```kotlin
stove {
  couchbase {
    // Pause the container
    pause()
    
    // Your application should handle the failure
    // ...
    
    // Unpause the container
    unpause()
    
    // Verify recovery
    shouldGet<User>("user:123") { user ->
      user.id shouldBe "123"
    }
  }
}
```

## Complete Example

Here's a complete <span data-rn="highlight" data-rn-color="#00968855" data-rn-duration="800">end-to-end test combining HTTP, Couchbase, and Kafka</span>:

```kotlin hl_lines="10 19 32 42"
test("should create product and store in couchbase") {
  stove {
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
      postAndExpectBody<Any>(
        uri = "/products",
        body = ProductCreateRequest(
          name = productName,
          price = 1299.99,
          categoryId = categoryId
        ).some()
      ) { response ->
        response.status shouldBe 200
      }
    }

    // Verify stored in Couchbase
    couchbase {
      shouldGet<Product>("products", "product:$productId") { product ->
        product.id shouldBe productId
        product.name shouldBe productName
        product.price shouldBe 1299.99
        product.categoryId shouldBe categoryId
      }
    }

    // Verify event was published
    kafka {
      shouldBePublished<ProductCreatedEvent>(atLeastIn = 10.seconds) {
        actual.id == productId &&
        actual.name == productName &&
        actual.price == 1299.99
      }
    }

    // Query products by category
    couchbase {
      shouldQuery<Product>(
        """
        SELECT p.* 
        FROM `products` p 
        WHERE p.categoryId = $categoryId
        """.trimIndent()
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
test("should use repository to save product") {
  stove {
    val productId = UUID.randomUUID().toString()
    val product = Product(id = productId, name = "Test Product", price = 99.99)

    // Use application's repository
    using<ProductRepository> {
      save(product)
    }

    // Verify in Couchbase
    couchbase {
      shouldGet<Product>("products", "product:$productId") { savedProduct ->
        savedProduct.id shouldBe productId
        savedProduct.name shouldBe "Test Product"
        savedProduct.price shouldBe 99.99
      }
    }
  }
}
```

## Advanced Operations

### Batch Operations

```kotlin
stove {
  couchbase {
    // Save multiple documents
    val users = listOf(
      User(id = "1", name = "Alice"),
      User(id = "2", name = "Bob"),
      User(id = "3", name = "Charlie")
    )
    
    users.forEach { user ->
      saveToDefaultCollection("user:${user.id}", user)
    }
    
    // Query all
    shouldQuery<User>("SELECT u.* FROM `${bucket().name}` u") { result ->
      result.size shouldBeGreaterThanOrEqual users.size
    }
    
    // Verify each
    users.forEach { user ->
      shouldGet<User>("user:${user.id}") { actual ->
        actual.name shouldBe user.name
      }
    }
  }
}
```

### Error Handling

```kotlin
stove {
  couchbase {
    // Document not found
    shouldNotExist("non-existent:key")
    
    // Attempting to delete non-existent document throws exception
    assertThrows<DocumentNotFoundException> {
      shouldDelete("non-existent:key")
    }
    
    // Attempting to assert non-existence on existing document throws assertion error
    saveToDefaultCollection("user:123", User(id = "123", name = "John"))
    assertThrows<AssertionError> {
      shouldNotExist("user:123")
    }
  }
}
```
