# MongoDB

=== "Gradle"

    ``` kotlin
        dependencies {
            testImplementation("com.trendyol:stove-mongodb:$version")
        }
    ```

## Configure

After getting the library from the maven source, while configuring TestSystem you will have access to `mongodb` function.
This function configures the MongoDB Docker container that is going to be started.

```kotlin
Stove()
  .with {
    mongodb {
      MongodbSystemOptions(
        configureExposedConfiguration = { cfg ->
          listOf(
            "mongodb.uri=${cfg.connectionString}",
            "mongodb.host=${cfg.host}",
            "mongodb.port=${cfg.port}"
          )
        }
      )
    }
  }
  .run()
```

### Container Options

Customize the MongoDB container:

```kotlin
Stove()
  .with {
    mongodb {
      MongodbSystemOptions(
        container = MongoContainerOptions(
          registry = "docker.io",
          image = "mongo",
          tag = "6.0",
          containerFn = { container ->
            // Additional container configuration
            container.withEnv("MONGO_INITDB_DATABASE", "testdb")
          }
        ),
        configureExposedConfiguration = { cfg ->
          listOf(
            "mongodb.uri=${cfg.connectionString}",
            "mongodb.host=${cfg.host}",
            "mongodb.port=${cfg.port}"
          )
        }
      )
    }
  }
  .run()
```

### Database Options

Configure the default database and collection:

```kotlin
Stove()
  .with {
    mongodb {
      MongodbSystemOptions(
        databaseOptions = DatabaseOptions(
          default = DatabaseOptions.DefaultDatabase(
            name = "myDatabase",
            collection = "myCollection"
          )
        ),
        configureExposedConfiguration = { cfg ->
          listOf(
            "mongodb.uri=${cfg.connectionString}"
          )
        }
      )
    }
  }
  .run()
```

### Custom Client Configuration

Customize the MongoDB client settings:

```kotlin
Stove()
  .with {
    mongodb {
      MongodbSystemOptions(
        configureClient = { settings ->
          settings.applyToConnectionPoolSettings { pool ->
            pool.maxSize(10)
            pool.minSize(1)
          }
          settings.applyToSocketSettings { socket ->
            socket.connectTimeout(10, TimeUnit.SECONDS)
            socket.readTimeout(30, TimeUnit.SECONDS)
          }
        },
        configureExposedConfiguration = { cfg ->
          listOf("mongodb.uri=${cfg.connectionString}")
        }
      )
    }
  }
  .run()
```

### Custom Serialization

Configure custom serialization for your documents:

```kotlin
Stove()
  .with {
    mongodb {
      val customSerde = StoveSerde.jackson.anyJsonStringSerde(
        StoveSerde.jackson.byConfiguring {
          disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          enable(MapperFeature.DEFAULT_VIEW_INCLUSION)
          registerModule(JavaTimeModule())
          registerModule(KotlinModule.Builder().build())
        }
      )
      
      MongodbSystemOptions(
        serde = customSerde,
        configureExposedConfiguration = { cfg ->
          listOf("mongodb.uri=${cfg.connectionString}")
        }
      )
    }
  }
  .run()
```

## Migrations

Stove provides a way to run migrations before tests start:

```kotlin
class CreateIndexesMigration : DatabaseMigration<MongodbMigrationContext> {
  override val order: Int = 1

  override suspend fun execute(connection: MongodbMigrationContext) {
    val db = connection.client.getDatabase(connection.options.databaseOptions.default.name)
    
    // Create indexes
    db.getCollection<Document>("users").createIndex(
      Indexes.ascending("email"),
      IndexOptions().unique(true)
    )
    
    db.getCollection<Document>("products").createIndex(
      Indexes.compoundIndex(
        Indexes.ascending("category"),
        Indexes.descending("createdAt")
      )
    )
  }
}
```

Register migrations in your Stove configuration:

```kotlin
Stove()
  .with {
    mongodb {
      MongodbSystemOptions(
        configureExposedConfiguration = { cfg ->
          listOf("mongodb.uri=${cfg.connectionString}")
        }
      ).migrations {
        register<CreateIndexesMigration>()
      }
    }
  }
  .run()
```

## Usage

### Saving Documents

Save documents to MongoDB collections:

```kotlin
data class User(
  val id: String,
  val name: String,
  val email: String,
  val age: Int
)

stove {
  mongodb {
    val userId = ObjectId().toHexString()
    
    // Save to default collection
    save(
      instance = User(id = userId, name = "John Doe", email = "john@example.com", age = 30),
      objectId = userId
    )
    
    // Save to specific collection
    save(
      instance = User(id = userId, name = "Jane Doe", email = "jane@example.com", age = 28),
      objectId = userId,
      collection = "users"
    )
  }
}
```

### Getting Documents

Retrieve and validate documents by ObjectId:

```kotlin
stove {
  mongodb {
    val userId = ObjectId().toHexString()
    
    // First save the document
    save(
      instance = User(id = userId, name = "John Doe", email = "john@example.com", age = 30),
      objectId = userId,
      collection = "users"
    )
    
    // Get from specific collection
    shouldGet<User>(objectId = userId, collection = "users") { user ->
      user.id shouldBe userId
      user.name shouldBe "John Doe"
      user.email shouldBe "john@example.com"
      user.age shouldBe 30
    }
  }
}
```

### Checking Non-Existence

Verify that documents don't exist:

```kotlin
stove {
  mongodb {
    val nonExistentId = ObjectId().toHexString()
    
    // Check default collection
    shouldNotExist(objectId = nonExistentId)
    
    // Check specific collection
    shouldNotExist(objectId = nonExistentId, collection = "users")
  }
}
```

### Deleting Documents

Delete documents and verify deletion:

```kotlin
stove {
  mongodb {
    val userId = ObjectId().toHexString()
    
    // Save a document
    save(
      instance = User(id = userId, name = "John Doe", email = "john@example.com", age = 30),
      objectId = userId,
      collection = "users"
    )
    
    // Delete it
    shouldDelete(objectId = userId, collection = "users")
    
    // Verify deletion
    shouldNotExist(objectId = userId, collection = "users")
  }
}
```

### Querying Documents

Query documents using MongoDB query syntax:

```kotlin
stove {
  mongodb {
    // Setup test data
    listOf(
      User(id = ObjectId().toHexString(), name = "Alice", email = "alice@example.com", age = 25),
      User(id = ObjectId().toHexString(), name = "Bob", email = "bob@example.com", age = 35),
      User(id = ObjectId().toHexString(), name = "Charlie", email = "charlie@example.com", age = 28)
    ).forEach { user ->
      save(instance = user, objectId = ObjectId().toHexString(), collection = "users")
    }
    
    // Simple query
    shouldQuery<User>(
      query = """{ "age": { "${'$'}gte": 30 } }""",
      collection = "users"
    ) { users ->
      users.size shouldBe 1
      users.first().name shouldBe "Bob"
    }
    
    // Query with multiple conditions
    shouldQuery<User>(
      query = """
        {
          "${'$'}and": [
            { "age": { "${'$'}gte": 25 } },
            { "age": { "${'$'}lte": 30 } }
          ]
        }
      """.trimIndent(),
      collection = "users"
    ) { users ->
      users.size shouldBe 2
      users.map { it.name } shouldContainAll listOf("Alice", "Charlie")
    }
  }
}
```

### Accessing the Client Directly

For advanced operations, access the MongoDB client:

```kotlin
stove {
  mongodb {
    val mongoClient = client()
    
    // Access the database
    val db = mongoClient.getDatabase("myDatabase")
    
    // List collections
    val collections = db.listCollectionNames().toList()
    
    // Perform custom operations
    db.getCollection<Document>("users")
      .find()
      .limit(10)
      .toList()
      .also { documents ->
        documents.size shouldBeLessThanOrEqual 10
      }
  }
}
```

### Pause and Unpause Container

Control the MongoDB container for testing failure scenarios:

```kotlin
stove {
  mongodb {
    val userId = ObjectId().toHexString()
    
    // MongoDB is running
    save(
      instance = User(id = userId, name = "John", email = "john@example.com", age = 30),
      objectId = userId,
      collection = "users"
    )
    
    // Pause the container
    pause()
    
    // Your application should handle the failure
    // ...
    
    // Unpause the container
    unpause()
    
    // Verify recovery
    shouldGet<User>(objectId = userId, collection = "users") { user ->
      user.name shouldBe "John"
    }
  }
}
```

!!! warning
    `pause()`, `unpause()`, and `inspect()` operations are not supported when using a provided instance.

### Container Inspection

Inspect the MongoDB container:

```kotlin
stove {
  mongodb {
    val info = inspect()
    info?.let {
      println("Container ID: ${it.containerId}")
      println("Network: ${it.network}")
      println("IP Address: ${it.ipAddress}")
    }
  }
}
```

## Complete Example

Here's a complete end-to-end test combining HTTP, MongoDB, and Kafka:

```kotlin
data class Product(
  val id: String,
  val name: String,
  val description: String,
  val price: Double,
  val categoryId: Int,
  val stock: Int,
  val createdAt: Instant = Instant.now()
)

test("should create product and store in mongodb") {
  stove {
    val productId = ObjectId().toHexString()
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
          description = "High-performance gaming laptop",
          price = 1299.99,
          categoryId = categoryId,
          stock = 10
        ).some()
      ) { response ->
        response.status shouldBe 201
        response.body().id shouldNotBe null
      }
    }

    // Verify stored in MongoDB
    mongodb {
      shouldQuery<Product>(
        query = """{ "name": "$productName" }""",
        collection = "products"
      ) { products ->
        products.size shouldBe 1
        products.first().also { product ->
          product.name shouldBe productName
          product.price shouldBe 1299.99
          product.categoryId shouldBe categoryId
          product.stock shouldBe 10
        }
      }
    }

    // Verify event was published
    kafka {
      shouldBePublished<ProductCreatedEvent>(atLeastIn = 10.seconds) {
        actual.name == productName &&
        actual.price == 1299.99
      }
    }

    // Update product stock via API
    http {
      putAndExpectBodilessResponse(
        uri = "/products/$productId/stock",
        body = UpdateStockRequest(quantity = -2).some()
      ) { response ->
        response.status shouldBe 200
      }
    }

    // Verify stock updated in MongoDB
    mongodb {
      shouldQuery<Product>(
        query = """{ "name": "$productName" }""",
        collection = "products"
      ) { products ->
        products.first().stock shouldBe 8
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
    val productId = ObjectId().toHexString()
    val product = Product(
      id = productId,
      name = "Test Product",
      description = "Test Description",
      price = 99.99,
      categoryId = 1,
      stock = 5
    )

    // Use application's repository
    using<ProductRepository> {
      save(product)
    }

    // Verify in MongoDB
    mongodb {
      shouldQuery<Product>(
        query = """{ "name": "Test Product" }""",
        collection = "products"
      ) { products ->
        products.size shouldBe 1
        products.first().id shouldBe productId
        products.first().price shouldBe 99.99
      }
    }
  }
}
```

## Advanced Operations

### Aggregation Queries

```kotlin
stove {
  mongodb {
    val mongoClient = client()
    val db = mongoClient.getDatabase("myDatabase")
    
    // Aggregation pipeline
    val pipeline = listOf(
      Aggregates.match(Filters.gte("price", 100)),
      Aggregates.group("${'$'}categoryId", 
        Accumulators.sum("totalProducts", 1),
        Accumulators.avg("avgPrice", "${'$'}price")
      ),
      Aggregates.sort(Sorts.descending("totalProducts"))
    )
    
    db.getCollection<Document>("products")
      .aggregate(pipeline)
      .toList()
      .also { results ->
        results.size shouldBeGreaterThan 0
        // Each result has categoryId, totalProducts, and avgPrice
      }
  }
}
```

### Bulk Operations

```kotlin
stove {
  mongodb {
    val mongoClient = client()
    val db = mongoClient.getDatabase("myDatabase")
    val collection = db.getCollection<Document>("users")
    
    // Bulk insert
    val users = (1..100).map { i ->
      Document()
        .append("_id", ObjectId())
        .append("name", "User $i")
        .append("email", "user$i@example.com")
        .append("age", 20 + (i % 50))
    }
    
    collection.insertMany(users)
    
    // Bulk update
    collection.updateMany(
      Filters.gte("age", 40),
      Updates.set("status", "senior")
    )
    
    // Verify
    val seniorCount = collection.countDocuments(Filters.eq("status", "senior"))
    seniorCount shouldBeGreaterThan 0
  }
}
```

### Transaction Support

```kotlin
stove {
  mongodb {
    val mongoClient = client()
    
    mongoClient.startSession().use { session ->
      session.startTransaction()
      try {
        val db = mongoClient.getDatabase("myDatabase")
        
        // Perform operations in transaction
        db.getCollection<Document>("accounts")
          .updateOne(
            session,
            Filters.eq("accountId", "sender"),
            Updates.inc("balance", -100.0)
          )
        
        db.getCollection<Document>("accounts")
          .updateOne(
            session,
            Filters.eq("accountId", "receiver"),
            Updates.inc("balance", 100.0)
          )
        
        session.commitTransaction()
      } catch (e: Exception) {
        session.abortTransaction()
        throw e
      }
    }
  }
}
```

### Working with Indexes

```kotlin
stove {
  mongodb {
    val mongoClient = client()
    val db = mongoClient.getDatabase("myDatabase")
    val collection = db.getCollection<Document>("users")
    
    // Create unique index
    collection.createIndex(
      Indexes.ascending("email"),
      IndexOptions().unique(true)
    )
    
    // Create compound index
    collection.createIndex(
      Indexes.compoundIndex(
        Indexes.ascending("status"),
        Indexes.descending("createdAt")
      )
    )
    
    // Create text index for search
    collection.createIndex(
      Indexes.text("name")
    )
    
    // List indexes
    collection.listIndexes().toList().also { indexes ->
      indexes.size shouldBeGreaterThan 1
    }
  }
}
```

## Provided Instance (External MongoDB)

For CI/CD pipelines or shared infrastructure:

```kotlin
Stove()
  .with {
    mongodb {
      MongodbSystemOptions.provided(
        connectionString = System.getenv("MONGODB_URI") ?: "mongodb://localhost:27017",
        host = System.getenv("MONGODB_HOST") ?: "localhost",
        port = System.getenv("MONGODB_PORT")?.toInt() ?: 27017,
        cleanup = { client ->
          // Clean up test data after tests
          client.getDatabase("testdb").drop()
        },
        configureExposedConfiguration = { cfg ->
          listOf(
            "mongodb.uri=${cfg.connectionString}",
            "mongodb.host=${cfg.host}",
            "mongodb.port=${cfg.port}"
          )
        }
      )
    }
  }
  .run()
```

## Error Handling

```kotlin
stove {
  mongodb {
    // Document not found
    val nonExistentId = ObjectId().toHexString()
    shouldNotExist(objectId = nonExistentId, collection = "users")
    
    // Attempting to get non-existent document throws exception
    assertThrows<NoSuchElementException> {
      shouldGet<User>(objectId = nonExistentId, collection = "users") { }
    }
    
    // Verify existence check on existing document
    val existingId = ObjectId().toHexString()
    save(
      instance = User(id = existingId, name = "Existing", email = "existing@example.com", age = 25),
      objectId = existingId,
      collection = "users"
    )
    
    assertThrows<AssertionError> {
      shouldNotExist(objectId = existingId, collection = "users")
    }
  }
}
```

## Working with ObjectId

MongoDB uses `ObjectId` as the default identifier. Stove handles this transparently:

```kotlin
data class UserWithStringId(
  val id: String, // String representation of ObjectId
  val name: String,
  val email: String
)

stove {
  mongodb {
    // Generate ObjectId
    val objectId = ObjectId()
    val stringId = objectId.toHexString()
    
    // Save with string ID
    save(
      instance = UserWithStringId(id = stringId, name = "Test", email = "test@example.com"),
      objectId = stringId,
      collection = "users"
    )
    
    // Retrieve using string ID
    shouldGet<UserWithStringId>(objectId = stringId, collection = "users") { user ->
      user.id shouldBe stringId
      user.name shouldBe "Test"
    }
  }
}
```
