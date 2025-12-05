# Troubleshooting & FAQ

This guide helps you diagnose and resolve common issues when working with Stove.

## Common Issues

### Docker Issues

#### Docker Not Found / Not Running

**Symptoms:**
```
Could not find a valid Docker environment
```

**Solutions:**

1. **Verify Docker is installed and running:**
   ```bash
   docker --version
   docker ps
   ```

2. **Check Docker daemon status:**
   ```bash
   # macOS/Linux
   systemctl status docker
   
   # or
   docker info
   ```

3. **Restart Docker Desktop** (if using Docker Desktop)

4. **Check Docker socket permissions:**
   ```bash
   # Linux
   sudo chmod 666 /var/run/docker.sock
   ```

#### Docker Image Pull Failures

**Symptoms:**
```
Error pulling image: denied: access denied
```

**Solutions:**

1. **Use a custom registry:**
   ```kotlin
   DEFAULT_REGISTRY = "your-registry.com"
   ```

2. **Login to registry:**
   ```bash
   docker login your-registry.com
   ```

3. **Configure per-component registry:**
   ```kotlin
   kafka {
       KafkaSystemOptions(
           container = KafkaContainerOptions(
               registry = "your-registry.com"
           )
       ) { /* config */ }
   }
   ```

#### Port Already in Use

**Symptoms:**
```
Bind for 0.0.0.0:8080 failed: port is already allocated
```

**Solutions:**

1. **Find and kill the process using the port:**
   ```bash
   # macOS/Linux
   lsof -i :8080
   kill -9 <PID>
   
   # Windows
   netstat -ano | findstr :8080
   taskkill /PID <PID> /F
   ```

2. **Use a different port:**
   ```kotlin
   springBoot(
       runner = { params -> myApp.run(params) },
       withParameters = listOf("server.port=8081")
   )
   ```

3. **Use dynamic ports:** Let the framework assign available ports when possible.

### Startup Issues

#### Application Fails to Start

**Symptoms:**
```
Application failed to start
```

**Solutions:**

1. **Check application logs:**
   ```kotlin
   springBoot(
       withParameters = listOf(
           "logging.level.root=debug",
           "logging.level.org.springframework=debug"
       )
   )
   ```

2. **Verify configuration is being passed correctly:**
   ```kotlin
   kafka {
       KafkaSystemOptions { cfg ->
           println("Kafka config: ${cfg.bootstrapServers}")  // Debug print
           listOf("kafka.bootstrapServers=${cfg.bootstrapServers}")
       }
   }
   ```

3. **Ensure your application accepts CLI arguments:**
   ```kotlin
   // Application should parse args
   fun run(args: Array<String>) {
       // args should include Stove's configuration
   }
   ```

#### Container Startup Timeout

**Symptoms:**
```
Container startup timed out
```

**Solutions:**

1. **Increase container startup timeout:**
   ```kotlin
   couchbase {
       CouchbaseSystemOptions(
           container = CouchbaseContainerOptions(
               containerFn = { container ->
                   container.withStartupTimeout(Duration.ofMinutes(5))
               }
           )
       ) { /* config */ }
   }
   ```

2. **Check container resource requirements:**
   - Elasticsearch needs at least 2GB RAM
   - Couchbase needs significant memory
   - Reduce memory limits in resource-constrained environments

3. **Check Docker resources:**
   - Increase Docker Desktop memory allocation
   - Ensure sufficient disk space

### Test Failures

#### Assertion Timeout

**Symptoms:**
```
Timed out waiting for condition
```

**Solutions:**

1. **Increase assertion timeout:**
   ```kotlin
   kafka {
       shouldBePublished<Event>(atLeastIn = 30.seconds) {
           actual.id == expectedId
       }
   }
   ```

2. **Check if the operation actually completes:**
   - Add logging to verify the operation is triggered
   - Check application logs for errors

3. **Verify async processing is working:**
   ```kotlin
   // Debug by checking intermediate state
   using<EventProcessor> {
       println("Pending events: ${pendingCount()}")
   }
   ```

#### Serialization/Deserialization Errors

**Symptoms:**
```
JsonParseException: Unrecognized field
MismatchedInputException: Cannot deserialize
```

**Solutions:**

1. **Align ObjectMapper configuration:**
   ```kotlin
   val objectMapper = ObjectMapper().apply {
       registerModule(KotlinModule.Builder().build())
       registerModule(JavaTimeModule())
       disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
   }
   
   TestSystem()
       .with {
           http {
               HttpClientSystemOptions(
                   contentConverter = JacksonConverter(objectMapper)
               )
           }
           kafka {
               KafkaSystemOptions(
                   serde = StoveSerde.jackson.anyByteArraySerde(objectMapper)
               ) { /* config */ }
           }
       }
   ```

2. **Check field name mapping:**
   ```kotlin
   data class MyEvent(
       @JsonProperty("eventId")  // Match exact field name
       val id: String
   )
   ```

3. **Verify data class has default constructor for Jackson:**
   ```kotlin
   // Add default values or use @JsonCreator
   data class MyEvent(
       val id: String = "",
       val name: String = ""
   )
   ```

#### Data Not Found

**Symptoms:**
```
Resource with key (xxx) is not found
Document not found
```

**Solutions:**

1. **Verify data was actually saved:**
   ```kotlin
   // Save
   couchbase {
       save(collection = "orders", id = orderId, instance = order)
       
       // Immediately verify
       shouldGet<Order>("orders", orderId) { o ->
           println("Saved order: $o")
       }
   }
   ```

2. **Check timing - wait for async operations:**
   ```kotlin
   // If save is async, wait for it
   delay(1.seconds)
   
   couchbase {
       shouldGet<Order>("orders", orderId) { /* verify */ }
   }
   ```

3. **Verify collection/index names match:**
   ```kotlin
   // Ensure collection names are consistent
   save(collection = "orders", ...)  // Note: "orders" not "order"
   shouldGet<Order>("orders", ...)   // Must match!
   ```

#### Kafka Message Not Found

**Symptoms:**
```
Message was not published within timeout
Message was not consumed within timeout
```

**Solutions:**

1. **Verify Kafka interceptor is configured:**
   ```kotlin
   // In TestInitializer
   class TestInitializer : BaseApplicationContextInitializer({
       bean<TestSystemInterceptor>(isPrimary = true)
   })
   ```

2. **Check topic names:**
   ```kotlin
   kafka {
       shouldBePublished<Event>(atLeastIn = 10.seconds) {
           println("Checking topic: ${metadata.topic}")  // Debug
           actual.id == expectedId
       }
   }
   ```

3. **Verify interceptor class is passed to application:**
   ```kotlin
   kafka {
       KafkaSystemOptions { cfg ->
           listOf(
               "kafka.bootstrapServers=${cfg.bootstrapServers}",
               "kafka.interceptorClasses=${cfg.interceptorClass}"  // Important!
           )
       }
   }
   ```

4. **Check consumer group offset configuration:**
   ```kotlin
   springBoot(
       withParameters = listOf(
           "kafka.offset=earliest",  // Start from beginning
           "kafka.autoCreateTopics=true"
       )
   )
   ```

#### WireMock Stubs Not Being Hit

**Symptoms:**
```
Connection refused to external service
Test timeout when calling mocked endpoint
Mock not found / unexpected request
```

**Cause:** This is almost always because your application's external service URLs don't match the WireMock URL.

**Solutions:**

1. **Ensure ALL external service URLs point to WireMock:**
   ```kotlin
   TestSystem()
       .with {
           wiremock {
               WireMockSystemOptions(port = 9090)
           }
           springBoot(
               runner = { params -> myApp.run(params) },
               withParameters = listOf(
                   // ALL external services must use WireMock URL
                   "payment.service.url=http://localhost:9090",
                   "inventory.service.url=http://localhost:9090",
                   "notification.service.url=http://localhost:9090"
               )
           )
       }
   ```

2. **Verify your application is reading the URLs from configuration:**
   ```kotlin
   // Your application should read URLs from config, not hardcode them
   @Value("\${payment.service.url}")
   private lateinit var paymentServiceUrl: String
   ```

3. **Check the port matches:**
   ```kotlin
   // WireMock port
   WireMockSystemOptions(port = 9090)
   
   // Application parameter must match
   "payment.service.url=http://localhost:9090"  // Same port!
   ```

4. **Debug by checking WireMock requests:**
   ```kotlin
   wiremock {
       // After test, check what requests WireMock received
       WireMock.getAllServeEvents().forEach { event ->
           println("Request: ${event.request.url}")
       }
   }
   ```

### Memory Issues

#### OutOfMemoryError

**Symptoms:**
```
java.lang.OutOfMemoryError: Java heap space
```

**Solutions:**

1. **Increase JVM heap for tests:**
   ```kotlin
   // build.gradle.kts
   tasks.test {
       jvmArgs("-Xmx2g", "-Xms512m")
   }
   ```

2. **Limit container memory:**
   ```kotlin
   elasticsearch {
       ElasticsearchSystemOptions(
           container = ElasticContainerOptions(
               containerFn = { container ->
                   container.withEnv("ES_JAVA_OPTS", "-Xms512m -Xmx512m")
               }
           )
       ) { /* config */ }
   }
   ```

3. **Use provided instances instead of containers** for CI environments.

### CI/CD Issues

#### Docker-in-Docker Not Working

**Solutions:**

1. **Use DinD sidecar in CI:**
   ```yaml
   # GitLab CI example
   services:
     - docker:dind
   variables:
     DOCKER_HOST: tcp://docker:2375
   ```

2. **Use provided instances:**
   ```kotlin
   TestSystem()
       .with {
           kafka {
               KafkaSystemOptions.provided(
                   bootstrapServers = System.getenv("KAFKA_SERVERS"),
                   configureExposedConfiguration = { cfg ->
                       listOf("kafka.bootstrapServers=${cfg.bootstrapServers}")
                   }
               )
           }
       }
   ```

#### Slow CI Builds

**Solutions:**

1. **Use provided instances** for external infrastructure
2. **Enable container reuse:**
   ```kotlin
   TestSystem {
       keepDependenciesRunning()  // In development only
   }
   ```
3. **Run tests in parallel** (ensure proper isolation)
4. **Use smaller container images** when available

#### Intermittent Failures with Shared Infrastructure

**Symptoms:**
```
Tests pass locally but fail randomly in CI
Data from another test run appears in assertions
"Topic already exists" or "Index already exists" errors
Tests fail when multiple builds run in parallel
```

**Cause:** Multiple test runs are using the same resource names (databases, topics, indices) in shared infrastructure.

**Solutions:**

1. **Use unique resource prefixes per test run:**
   ```kotlin
   object TestRunContext {
       val runId: String = System.getenv("CI_JOB_ID") 
           ?: UUID.randomUUID().toString().take(8)
       
       val databaseName = "testdb_$runId"
       val topicPrefix = "test_${runId}_"
       val indexPrefix = "test_${runId}_"
   }
   ```

2. **Apply prefixes to all resources:**
   ```kotlin
   springBoot(
       withParameters = listOf(
           "spring.datasource.url=jdbc:postgresql://db:5432/${TestRunContext.databaseName}",
           "kafka.topic.orders=${TestRunContext.topicPrefix}orders",
           "elasticsearch.index.products=${TestRunContext.indexPrefix}products"
       )
   )
   ```

3. **Clean up only your resources:**
   ```kotlin
   cleanup = { admin ->
       val ourTopics = admin.listTopics().names().get()
           .filter { it.startsWith(TestRunContext.topicPrefix) }
       if (ourTopics.isNotEmpty()) {
           admin.deleteTopics(ourTopics).all().get()
       }
   }
   ```

4. **Log the run ID for debugging:**
   ```kotlin
   init {
       println("Test Run ID: ${TestRunContext.runId}")
   }
   ```

!!! tip "Detailed Guide"
    See [Provided Instances - Test Isolation](Components/11-provided-instances.md#test-isolation-with-shared-infrastructure) for comprehensive examples.

## FAQ

### General Questions

#### Q: Can I use Stove with Java?

**A:** Yes, you can use Stove in Java projects! However, the e2e tests themselves need to be written in Kotlin. Stove's DSL is designed specifically for Kotlin, providing a clean and expressive syntax:

```kotlin
class MyE2ETest : FunSpec({
    test("should create order") {
        TestSystem.validate {
            http {
                postAndExpectBodilessResponse(
                    uri = "/orders",
                    body = Some(CreateOrderRequest()),
                    expect = { status shouldBe 201 }
                )
            }
        }
    }
})
```

You can still test your Java application with Stove - just write your e2e test files in Kotlin.

#### Q: Can I use JUnit instead of Kotest?

**A:** Yes, Stove works with both JUnit and Kotest. See the [Getting Started](getting-started.md) guide for JUnit examples.

#### Q: How do I debug tests?

**A:** 

1. Set breakpoints in your application code
2. Run tests in debug mode
3. Use verbose logging:
   ```kotlin
   withParameters = listOf("logging.level.root=debug")
   ```
4. Access application beans:
   ```kotlin
   using<MyService> {
       println("Service state: $this")
   }
   ```

#### Q: Can I run tests in parallel?

**A:** Yes, but ensure proper test isolation:

- Use unique test data (UUIDs)
- Don't share state between tests
- Be careful with shared resources

#### Q: How do I test with SSL/TLS?

**A:** Configure the component with security enabled:

```kotlin
elasticsearch {
    ElasticsearchSystemOptions(
        container = ElasticContainerOptions(
            disableSecurity = false
        ),
        configureExposedConfiguration = { cfg ->
            // Certificate info available in cfg.certificate
            listOf(...)
        }
    )
}
```

### Component-Specific Questions

#### Q: Why isn't my Kafka message being intercepted?

**A:** Ensure:

1. `TestSystemInterceptor` is registered as a bean
2. `kafka.interceptorClasses` is configured correctly
3. Your Kafka listener container uses the interceptor

```kotlin
// Application configuration
@Bean
fun containerFactory(
    interceptor: ConsumerAwareRecordInterceptor<String, String>
): ConcurrentKafkaListenerContainerFactory<String, String> {
    return ConcurrentKafkaListenerContainerFactory<String, String>().apply {
        setRecordInterceptor(interceptor)
    }
}
```

#### Q: How do I test multiple databases?

**A:** Add multiple database components:

```kotlin
TestSystem()
    .with {
        postgresql { PostgresqlOptions(...) }
        mongodb { MongodbSystemOptions(...) }
        couchbase { CouchbaseSystemOptions(...) }
    }
```

#### Q: Can I use custom container images?

**A:** Yes:

```kotlin
kafka {
    KafkaSystemOptions(
        container = KafkaContainerOptions(
            registry = "my-registry.com",
            image = "custom/kafka",
            tag = "3.5.0"
        )
    ) { /* config */ }
}
```

#### Q: How do I handle database migrations?

**A:** Use the migrations API:

```kotlin
postgresql {
    PostgresqlOptions(...).migrations {
        register<CreateUserTableMigration>()
        register<CreateOrderTableMigration>()
    }
}
```

#### Q: Can I access the underlying testcontainer?

**A:** For container operations like pause/unpause:

```kotlin
couchbase {
    pause()    // Pause container
    unpause()  // Resume container
}
```

For the client:
```kotlin
elasticsearch {
    val client = client()  // Get Elasticsearch client
    // Use client directly
}
```

### Performance Questions

#### Q: How can I speed up test execution?

**A:**

1. **Keep containers running:**
   ```kotlin
   TestSystem { keepDependenciesRunning() }
   ```

2. **Use provided instances in CI:**
   ```kotlin
   kafka { KafkaSystemOptions.provided(bootstrapServers = "...", configureExposedConfiguration = { ... }) }
   ```

3. **Reduce container resource allocation:**
   ```kotlin
   withEnv("ES_JAVA_OPTS", "-Xms256m -Xmx256m")
   ```

4. **Run independent tests in parallel**

#### Q: Why is container startup slow?

**A:** Container startup depends on:

- Image pull time (first run)
- Container initialization time
- Health check completion

Solutions:
- Pre-pull images in CI
- Use `keepDependenciesRunning()` locally
- Increase startup timeout for slow containers

### Migration Questions

#### Q: How do I migrate from 0.14.x to 0.15.x?

**A:** See [Migration Notes](release-notes/0.15.0.md) for detailed instructions. Key changes:

- `StoveSerde` replaces direct `ObjectMapper` usage
- Configure serde for each component that needs it

## Getting Help

If you can't find a solution:

1. **Search existing issues:** [GitHub Issues](https://github.com/Trendyol/stove/issues)
2. **Check examples:** [Examples Directory](https://github.com/Trendyol/stove/tree/main/examples)
3. **Open a new issue:** Include:
   - Stove version
   - JDK version
   - Docker version
   - Complete error message
   - Minimal reproduction code

## Debug Checklist

When troubleshooting, check these items:

- [ ] Docker is running and accessible
- [ ] Correct Stove version in dependencies
- [ ] Application main function is properly modified
- [ ] Configuration is passed to application
- [ ] Serializers match between Stove and application
- [ ] Container has enough resources
- [ ] Ports are not conflicting
- [ ] Network is accessible (for provided instances)
- [ ] Timeouts are appropriate for your environment
