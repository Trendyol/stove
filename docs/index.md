# Stove

Stove is an end-to-end testing framework that spins up your application along with all its physical dependencies (databases, message queues, etc.) so you can test the real thing. Everything is controlled through Kotlin code, giving you full control over your test environment.

Since JVM languages can interoperate, your application and tests don't need to use the same language. Write your app in Java and tests in Kotlin, or mix Scala and Kotlin—whatever works for your team. Stove takes advantage of this and lets you write all your tests in Kotlin, regardless of what your application is written in.

Your tests stay infrastructure-agnostic but component-aware. You can easily plug in whatever physical components you need using Stove's APIs. All the infrastructure is **pluggable**—add what you need, skip what you don't. If Stove doesn't have a component you need, you can build your own using the abstractions it provides.

The only hard requirement is Docker, since Stove uses [testcontainers](https://github.com/testcontainers/testcontainers-java) under the hood.

You can run tests with either JUnit or Kotest. CI works too, though you'll need **DinD (docker-in-docker)** setup for that.

Want to know more about why we built Stove? Check out the [Medium article](https://medium.com/trendyol-tech/a-new-approach-to-the-api-end-to-end-testing-in-kotlin-f743fd1901f5) that explains the motivation behind the framework.

!!! note "Not a Replacement for Unit Tests"
    Stove is for end-to-end and component tests, not unit tests. You'll still want unit tests for fast feedback on individual components.

## Why Stove?

The JVM ecosystem has great frameworks for building applications, but when it comes to integration, component, or e2e testing, there's no single framework that works across all tech stacks. Testcontainers exists, but you still end up writing a lot of boilerplate to wire it up with your specific stack.

We built Stove to solve this. We wanted to boost developer productivity while keeping code quality high, and we needed it to work across different tech stacks:

- Kotlin apps with Spring Boot
- Kotlin apps with Ktor
- Java apps with Spring Boot
- Java apps with Micronaut
- Java apps with Quarkus
- Scala apps with Spring Boot

Every time someone wants to write e2e tests, they end up writing the same boilerplate: starting physical components, figuring out how to start the application from tests, accessing application beans, and so on. Stove eliminates all that by providing a single API that works across all these stacks.

**Stove unifies the testing experience, no matter what you're using.**

## High Level Architecture

![img](./assets/stove_architecture.svg)

## Building from Source

To build Stove from source, you'll need:

- JDK 17+
- Docker (latest version recommended)

Then just run:

```shell
./gradlew build  # This builds and runs all tests
```

## Getting Started

### Pre-requisites

- JDK 17+
- Docker for running the tests (please use the latest version)
- Kotlin 1.8+
- Gradle or Maven for running the tests, but Gradle is recommended.
    - Gradle is the default build tool for Stove, and it is used in the examples.
    - If you are using Intellij IDEA, Kotest plugin is recommended.

The framework is still evolving, but it's working well and is actively used at Trendyol. Since Stove tests live in your test source set (separate from your application code), trying it out is completely risk-free—give it a shot!

`$version = please check the current version`

Versions are available at [Releases](https://github.com/Trendyol/stove/releases)

!!! Tip

    You can use SNAPSHOT versions for the latest features. As of 5th June 2025, Stove's snapshot packages are hosted on 
    [Central Sonatype](https://central.sonatype.com/service/rest/repository/browse/maven-snapshots/com/trendyol/).
    SNAPSHOT versions are released with the `1.0.0.{buildNumber}-SNAPSHOT` strategy.
  
      ```kotlin
      repositories {
          maven("https://central.sonatype.com/repository/maven-snapshots")
      }
      ```

Every physical component you might need for testing is a separate module in Stove. Add only what you need.

Stove supports these components:

- [Kafka](Components/02-kafka.md)
- [MongoDB](Components/07-mongodb.md)
- [MSSQL](Components/08-mssql.md)
- [PostgreSQL](Components/06-postgresql.md)
- [MySQL](Components/16-mysql.md)
- [Redis](Components/09-redis.md)
- [Elasticsearch](Components/03-elasticsearch.md)
- [Couchbase](Components/01-couchbase.md)
- [WireMock](Components/04-wiremock.md)
- [HTTP](Components/05-http.md)
- [gRPC](Components/12-grpc.md)
- [gRPC Mocking](Components/14-grpc-mock.md)
- [Bridge](Components/10-bridge.md)
- [Reporting](Components/13-reporting.md)
- [Distributed Tracing](Components/15-tracing.md)

=== "Gradle"

    ```kotlin
    repositories {
      mavenCentral()
    }

    dependencies {
      // Import BOM for version management
      testImplementation(platform("com.trendyol:stove-bom:$version"))
      
      // Application Under Test
      
      // Spring Boot
      testImplementation("com.trendyol:stove-spring")
      
      // or

      // Ktor
      testImplementation("com.trendyol:stove-ktor")
      
      // Components
      testImplementation("com.trendyol:stove")
      testImplementation("com.trendyol:stove-kafka")
      testImplementation("com.trendyol:stove-mongodb")
      testImplementation("com.trendyol:stove-mssql")
      testImplementation("com.trendyol:stove-postgres")
      testImplementation("com.trendyol:stove-redis")
      testImplementation("com.trendyol:stove-elasticsearch")
      testImplementation("com.trendyol:stove-couchbase")
      testImplementation("com.trendyol:stove-wiremock")
      testImplementation("com.trendyol:stove-http")
    }
    ```

### How To Write Tests?

Stove uses your application entrance point to start your application alongside the physical components. The
application's `main` is the entrance point for the applications in general.

Everything starts with the `Stove` class. You configure your system using the `with` function.

```kotlin
Stove()
  .with {
    // your configurations depending on the dependencies you need
  }.run()
```

The `with` function is a lambda where you configure your system. You can add physical components here, and it's also where you plug in any custom **systems** you might want to create.

If you've added the `com.trendyol:stove-kafka` package, you can use the `kafka` function in the `with` block:

```kotlin
Stove()
  .with {
    kafka {
      // your kafka configurations
    }
  }.run()
```

!!! Note

    You can add multiple physical components in the `with` block. Think of it as a DSL for your test system and a
    `docker-compose` in Kotlin.

!!! tip

    If you want to jump directly to the examples, you can check the examples in the repository.

    - [Examples](https://github.com/Trendyol/stove/tree/main/examples)
        - [Ktor Example](https://github.com/Trendyol/stove/tree/main/examples/ktor-example)
        - [Spring Boot Example](https://github.com/Trendyol/stove/tree/main/examples/spring-example)
        - [Spring Boot with Standalone Kafka](https://github.com/Trendyol/stove/tree/main/examples/spring-standalone-example)
        
      - [Recipes](https://github.com/Trendyol/stove/tree/main/recipes)
          - [Java Recipes](https://github.com/Trendyol/stove/tree/main/recipes/java-recipes)
          - [Kotlin Recipes](https://github.com/Trendyol/stove/tree/main/recipes/kotlin-recipes)
          - [Scala Recipes](https://github.com/Trendyol/stove/tree/main/recipes/scala-recipes)

Stove has the concept of _"Application Aware Testing"_. It means that Stove is aware of the application's entrance point
and that is the only information it needs to start the application.

Application that is being tested is a Spring Boot, Ktor, Micronaut, Quarkus, etc. and is called
"Application Under Test (AUT)".

The tests are agnostic to the application's framework.
Right now Stove supports Spring Boot, Ktor. But it is easy to add new frameworks.

!!! Note

    If you want to add a new framework, you can check the
    `com.trendyol.stove.system.abstractions.ApplicationUnderTest` interface.
    You can implement this interface for your framework.

Let's create an example for a Spring-Boot application with Kafka and explain the setup flow.

The dependencies we will need in the `build.gradle.kts` file are:

```kotlin
 dependencies {
  // Import BOM for version management
  testImplementation(platform("com.trendyol:stove-bom:$version"))
  
  testImplementation("com.trendyol:stove")
  testImplementation("com.trendyol:stove-kafka")
  testImplementation("com.trendyol:stove-http")
  testImplementation("com.trendyol:stove-spring")
}
```

```kotlin
Stove()
  .with {
    httpClient {
      HttpClientSystemOptions(
        baseUrl = "http://localhost:8001"
      )
    }

    kafka {
      KafkaSystemOptions(
        containerOptions = KafkaContainerOptions(tag = "latest"),
      ) {
        listOf(
          "kafka.bootstrapServers=${it.bootstrapServers}",
          "kafka.isSecure=false",
          "kafka.interceptorClasses=${it.interceptorClass}",
          "kafka.heartbeatInSeconds=2",
          "kafka.autoCreateTopics=true",
          "kafka.offset=earliest",
          "kafka.secureKafka=false"
        )
      }
    }

    bridge()

    springBoot( // Application Under Test
      runner = { parameters ->
        stove.spring.standalone.example.run(parameters)
      },
      withParameters = listOf(
        "server.port=8001",
        "logging.level.root=info",
        "logging.level.org.springframework.web=info",
        "spring.profiles.active=default",
      )
    )
  }
  .run()
```

The typical setup for a Spring Boot application with Kafka is like this. You can see that we have a `httpClient`function
that is used for the HTTP client against the application's endpoints.
Then we have a `kafka` function that is used for the Kafka setup.
Then we have a `bridge` function that is used for accessing the DI container of the application.
Then we have a `springBoot` function that is used for the Spring Boot application setup.

#### `httpClient` function

It is used for the HTTP client against the application's endpoints. You can configure the base URL of the application.
When the application is started, the base URL is used for the HTTP client.

#### `kafka` function

It is used for the Kafka setup. You can configure the Kafka container options and the Kafka properties. When the
application is started, the Kafka container is started and the Kafka properties are used for the application.
We will investigate the Kafka setup in detail in the [Kafka](Components/02-kafka.md/) section. Your application code
should be able to read these properties, and event app code needs to be arranged for this.

!!! tip "Is my application code e2e testing friendly?"

    In general, to write proper unit tests your code should be testable. 
    This means extracting dependencies to interfaces and using dependency injection. 
    
    Injecting time, configuration, and other dependencies is a good practice. 
    This makes your classes testable and you can easily replace the implementations of the interfaces in the tests.
    
    Changing your configuration to be able to read from the environment variables or CLI arguments is also a good practice.
    Since Stove is also passing down the test configurations and the optimum setup for the tests, your application should be able to read these configurations.
    
    So, don't think that you're changing too much application code just for sake of the Stove tests, you're making your application code e2e test friendly.

#### `bridge` function

This function is used for accessing the DI container of the application. When the application is started, the bridge is
created and the DI container is accessed in the tests.

If you want to access to the beans of the application, you can simply do:

```kotlin
stove {
  using<UserApplicationService> {
    this.getUserById(1) shouldBe User(1, "John", "Doe")
  }

  using<ProductDomainService, ProductRepository> { productDomainService, productRepository ->
    productDomainService.getProductById(1) shouldBe Product(1, "Product 1")
    productRepository.findById(1) shouldBe Product(1, "Product 1")
  }
}
```

#### `springBoot` function

This function is used for the Spring Boot application setup. You can configure the runner function and the parameters of
the application.
When the application is started, the runner function is called with the parameters.
The parameters you see in `runner` function are the parameters that are passed to the Spring Boot application when it is
started.
Each physical component exposes its own properties and you can use them in the application.
Here:

```kotlin
kafka {
  KafkaSystemOptions(
    containerOptions = KafkaContainerOptions(tag = "latest"),
  ) {
    listOf(
      "kafka.bootstrapServers=${it.bootstrapServers}",
      "kafka.isSecure=false",
      "kafka.interceptorClasses=${it.interceptorClass}",
      "kafka.heartbeatInSeconds=2",
      "kafka.autoCreateTopics=true",
      "kafka.offset=earliest",
      "kafka.secureKafka=false"
    )
  }
}
```

The list of properties are exposed by the Kafka component and you can use them in the application.
The `it` reference in this block is the physical component itself with all its exposed properties. When Kafka and the testing suite start, these properties are automatically passed down to your application.

### `run` function

Runs the entire setup. It starts the physical components and the application.

!!! warning "Run the Setup Once"

    You should run the setup once in your test suite.
    You can run it in the `@BeforeAll` function of JUnit or implement `AbstractProjectConfig#beforeProject` in Kotest.
    Teardown is also important to call. You can run it in the `@AfterAll` function of JUnit or implement
    `AbstractProjectConfig#afterProject` in Kotest.
    Simply calling `Stove.stop()` is enough to stop everything.

### Writing Tests

After the setup is done, you can write your tests. Use the `stove` function to write your test assertions:

```kotlin
stove {
  http {
    get<String>("/hello/index") { actual ->
      actual shouldContain "Hi from Stove framework"
    }
  }

  kafka {
    shouldBeConsumed<ProductCreatedEvent> { actual ->
      actual.productId == 1
    }
  }

  using<UserApplicationService> {
    this.getUserById(1) shouldBe User(1, "John", "Doe")
  }

  using<ProductDomainService, ProductRepository> { productDomainService, productRepository ->
    productDomainService.getProductById(1) shouldBe Product(1, "Product 1")
    productRepository.findById(1) shouldBe Product(1, "Product 1")
  }

  kafka {
    shouldBePublished<ProductCreatedEvent> { actual ->
      actual.productId == 1
    }
  }
}
```

That's it! You have up-and-running API, can be tested with Stove. And each test is independent of each other.
But they share the same instance of physical component of course, so you need to provide **random** data for each test.
This is a good practice for the tests to be independent of each other.

## Application Aware Testing

Stove is aware of your application either it is SpringBoot or Ktor, and it is aware of the entrance point of your
application.

There are entry point for every application, usually a `main` method that is invoked, and starts the application
lifecycle.

If you are publishing your `application` as a docker image, `docker run ...` basically runs your
application highly likely with a `jvm/java` command.

In this approach, we're using the same `main` function of your application in the test context to run the application as
full-blown as if it is invoked from outside.

Stove calls your application's `main` function like you would call `java yourApplicationName.jar --param1 --param2` to
run the application
from the test context. So the runner is JUnit or Kotest.

For Stove to attach properly to your application, application's main function needs to allow that. This does not change
behaviour at all, it just opens a door for e2e testing framework to _enter_.

This approach has lots of benefits besides of providing a debug ability while e2e testing. You can:

- Debug the application code
- Replace the implementations of the interfaces. Useful for time-bounded implementations such as schedulers, background
  workers, and time itself.
  you would only have consuming.
- Use and expose application's dependency container to the test context. You can access the beans of the application
  easily. Using `bridge` functionality.

### Spring Boot

You need to add the Stove-Spring dependency to be able to write e2e tests for the Spring application.

=== "Gradle"

    ```kotlin
    dependencies {
        testImplementation("com.trendyol:stove-spring:$version")
        
        // You can add other components if you need
    }
    ```

#### Tuning the application's entry point

Let's say the application has a standard `main` function, here how we will change it:

=== "Before"

    ```kotlin
    @SpringBootApplication
    class ExampleApplication

    fun main(args: Array<String>) { runApplication<ExampleApplication>(*args) }
    ```

=== "After"

    ```kotlin
    @SpringBootApplication
    class ExampleApplication

    fun main(args: Array<String>) { run(args) }

    fun run(
         args: Array<String>,
         init: SpringApplication.() -> Unit = {},
      ): ConfigurableApplicationContext {
            return runApplication<ExampleApplication>(*args, init = init)
        }
    ```

As you can see from `before-after` sections, we have divided the application main function into two parts.

`run(args, init)` method is the important point for the testing configuration. `init` allows us to override any
dependency
from the testing side that is being `time` related or `configuration` related. Spring itself opens this configuration
higher order function to the outside.

Also returning `ConfigurableApplicationContext` is important for the `bridge` functionality that we will use in the
tests.

!!! tip

    [Here](https://github.com/Trendyol/stove/tree/main/examples/spring-example) you can jump immediately to the Spring example application.

#### Initial Configuration

After you've added the dependencies, and configured the application's `main` function,
it is time to run your application for the first time from the test-context with Stove.

##### Setting up Stove for the Runner

=== "Kotest"

    It implements `AbstractProjectConfig` from Kotest framework that allows us to spin up Stove per run. This is project
    wide operation and executes **only one time**, as the name implies `beforeProject`.
    
    ```kotlin
    import com.trendyol.stove.extensions.kotest.StoveKotestExtension
    import com.trendyol.stove.system.Stove
    import com.trendyol.stove.http.*
    import com.trendyol.stove.spring.springBoot
    
    class Stove : AbstractProjectConfig() {
        // Register StoveKotestExtension for detailed failure reports
        override val extensions: List<Extension> = listOf(StoveKotestExtension())
            
        override suspend fun beforeProject(): Unit = 
            Stove()
                .with {
                    httpClient {
                        HttpClientSystemOptions (
                            baseUrl = "http://localhost:8001"
                        )
                    }
                    springBoot(
                        runner = { parameters ->
                            /* 
                            *  As you remember, we have divided application's main 
                            *  function into two parts, main and run. 
                            *  We use `run` invocation here.
                            * */
                            stove.spring.example.run(parameters)
                        },
                        withParameters = listOf(
                            "server.port=8001",
                            "logging.level.root=warn",
                            "logging.level.org.springframework.web=warn",
                            "spring.profiles.active=default"
                        )
                    )
                }.run()
    
        override suspend fun afterProject(): Unit = Stove.stop()
    }
    ```

=== "JUnit"

    ```kotlin
    import com.trendyol.stove.extensions.junit.StoveJUnitExtension
    import com.trendyol.stove.system.Stove
    import com.trendyol.stove.http.*
    import com.trendyol.stove.spring.springBoot
    import org.junit.jupiter.api.extension.ExtendWith
    
    @ExtendWith(StoveJUnitExtension::class)
    class StoveConfig {
    
        @BeforeAll
        fun beforeProject() = runBlocking {
             Stove()
                .with {
                    httpClient {
                        HttpClientSystemOptions (
                            baseUrl = "http://localhost:8001"
                        )
                    }
                    springBoot(
                        runner = { parameters ->
                            /* 
                            *  As you remember, we have divided application's main 
                            *  function into two parts, main and run. 
                            *  We use `run` invocation here.
                            * */
                            stove.spring.example.run(parameters)
                        },
                        withParameters = listOf(
                            "server.port=8001",
                            "logging.level.root=warn",
                            "logging.level.org.springframework.web=warn",
                            "spring.profiles.active=default"
                        )
                    )
                }.run()
        }
    
        @AfterAll
        fun afterProject() = runBlocking {
            Stove.stop()
        }
    }
    ```

In the `springBoot` function, we configure the application's entry point and the parameters passed to it. `stove.spring.example.run(parameters)` is where your application starts.

This is similar to the "service under test" concept from TDD, but since we're testing the entire system, we call it the "Application Under Test". Here we're configuring the Spring Boot application as the application under test.

!!! note

    `server.port=8001` is a Spring config, and Stove's `baseUrl` needs to match it, since HTTP requests are made
     against the `baseUrl` you define. `httpClient` creates a WebClient and uses the `baseUrl` you pass.

##### Writing Tests

Here is an example test that validates `http://localhost:$port/hello/index` returns the expected text
=== "Kotest"

    ```kotlin
    class ExampleTest: FunSpec({

        test("should return hi"){
            stove {
                http {
                    get<String>("/hello/index") { actual -> 
                        actual shouldContain "Hi from Stove framework" 
                    }
                }
    })
    ```

=== "JUnit"

    ```kotlin
    class ExampleTest {

        @Test
        fun `should return hi`() {
            stove {
                http {
                    get<String>("/hello/index") { actual -> 
                        actual shouldContain "Hi from Stove framework" 
                    }
                }
        }
    })
    ```

That's it! You have up-and-running API, can be tested with Stove.

!!! tip

    DSL scopes can appear more than once while writing the tests. 
    You can access to any DSL assertion scope such as http, kafka, using, etc. as many times as you need.

    Example:

    ```kotlin
    stove {
        http {
            get<String>("/hello/index") { actual -> 
                actual shouldContain "Hi from Stove framework" 
            }
        }
        
        kafka {
            shouldBeConsumed<ProductCreatedEvent> { actual -> 
                actual.productId == 1
            }
        }

        kafka {
            shouldBeConsumed<ProductCreatedEvent> { actual -> 
                actual.productId == 1
            }
        }

        http {
            get<String>("/hello/index") { actual -> 
                actual shouldContain "Hi from Stove framework" 
            }
        }
        
        using<UserApplicationService> {
            this.getUserById(1) shouldBe
        }
    ```

### Ktor

=== "Gradle"

    ```kotlin
    dependencies {
        testImplementation("com.trendyol:stove-ktor:$version")
        
        // Add your preferred DI framework (one of):
        testImplementation("io.insert-koin:koin-ktor:$koinVersion")  // Koin
        // OR
        testImplementation("io.ktor:ktor-server-di:$ktorVersion")    // Ktor-DI
        
        // You can add other components if you need
    }
    ```

!!! note "DI Framework Required"
    Ktor Bridge requires either Koin or Ktor-DI to be on the classpath. The `bridge()` function auto-detects which one is available.

#### Example Setup

=== "Koin"

    ```kotlin
    Stove()
      .with {
        bridge()  // Auto-detects Koin
        ktor(
          withParameters = listOf("port=8080"),
          runner = { parameters ->
            stove.ktor.example.run(
              parameters,
              testModules = listOf(
                module {
                  single<TimeProvider>(override = true) { FixedTimeProvider() }
                }
              )
            )
          }
        )
      }.run()
    ```

=== "Ktor-DI"

    ```kotlin
    Stove()
      .with {
        bridge()  // Auto-detects Ktor-DI
        ktor(
          withParameters = listOf("port=8080"),
          runner = { parameters ->
            stove.ktor.example.run(parameters) {
              provide<TimeProvider> { FixedTimeProvider() }
            }
          }
        )
      }.run()
    ```

After you've added `stove-ktor` dependency and your preferred DI framework, and configured the application's `main` function for Stove to enter, it is time to run your application for the first time from the test-context with Stove.

#### Tuning the application's entry point

Let's say the application has a standard `main` function, here how we will change it:

=== "Before"

    ```kotlin
    fun main() {
      embeddedServer(Netty, port = 8080) {
          routing {
              get("/") {
                  call.respondText("Hello, world!")
              }
          }
       }.start(wait = true)
    }
    ```

=== "After (Koin)"

    ```kotlin
    object ExampleApp {
      @JvmStatic
      fun main(args: Array<String>) = run(args)
        
      fun run(
        args: Array<String>, 
        wait: Boolean = true, 
        testModules: List<Module> = emptyList()  // Accept test modules
      ): Application {
        val config = loadConfiguration<Env>(args)
        return embeddedServer(Netty, port = config.port) {
          install(Koin) {
            modules(appModule, *testModules.toTypedArray())
          }
          configureRouting()
        }.start(wait = wait).application
      }
    }
    ```

=== "After (Ktor-DI)"

    ```kotlin
    object ExampleApp {
      @JvmStatic
      fun main(args: Array<String>) = run(args)
        
      fun run(
        args: Array<String>, 
        wait: Boolean = true, 
        testDependencies: (DependencyRegistrar.() -> Unit)? = null  // Accept test overrides
      ): Application {
        val config = loadConfiguration<Env>(args)
        return embeddedServer(Netty, port = config.port) {
          install(DI) {
            dependencies {
              provide<MyService> { MyServiceImpl() }
              testDependencies?.invoke(this)  // Apply test overrides
            }
          }
          configureRouting()
        }.start(wait = wait).application
      }
    }
    ```

As you can see from `before-after` sections, we have divided the application main function into two parts.
The `run` method accepts parameters for test configuration, allowing you to override dependencies from the testing side (e.g., time-related or configuration-related beans).

!!! note

    There are helper methods here for example [loadConfiguration](https://github.com/Trendyol/stove/blob/main/recipes/kotlin-recipes/ktor-recipe/src/main/kotlin/com/trendyol/stove/examples/kotlin/ktor/infra/boilerplate/util.kt) 
    that is used to load the configuration from the environment variables or CLI arguments. And as you can see there is an `Env` data class to cast the configuration. 
    Stove itself does not provide them, but of course we have already used them in our examples, you can find them in the examples.

!!! tip

    [Here](https://github.com/Trendyol/stove/tree/main/examples/ktor-example) you can jump immediately to the Ktor example application.

## Advanced

### Global Variables

#### DEFAULT_REGISTRY

The default container registry is `docker.io`. You can change it by setting the `DEFAULT_REGISTRY` variable.

```kotlin
DEFAULT_REGISTRY = "your.registry.com"
```

This will effect all the components Stove wide. Or you can set it for each individual component by setting the`registry`
property, example for Kafka:

```kotlin
KafkaSystemOptions(
  containerOptions = KafkaContainerOptions(
    registry = "your.registry.com",
    tag = "latest"
  ),
)
```

### Serializing and Deserializing

Each component has its own serialization and deserialization mechanism. You can align Stove's serialization and
deserialization mechanism with your application's serialization and deserialization mechanism.

Stove works with multiple serializers and deserializers. The package `stove` provides the following
serializers and deserializers:

* Jackson
* Gson
* Kotlinx

Stove also provides a way to add your own serializer and deserializer. You can implement the `StoveSerde<TIn, TOut>`
interface
and add your own serializer and deserializer.

`StoveSerde` also keeps the reference to the aforementioned libraries:

```kotlin
StoveSerde.jackson
StoveSerde.gson
StoveSerde.kotlinx
```

And also provides default implementations for them:

```kotlin
StoveSerde.jackson.anyByteArraySerde(yourObjectMapper())
StoveSerde.gson.anyByteArraySerde(yourGson())
StoveSerde.kotlinx.anyByteArraySerde(yourJson())

// there is also string serde
StoveSerde.jackson.anyStringSerde(yourObjectMapper())
StoveSerde.gson.anyStringSerde(yourGson())
StoveSerde.kotlinx.anyStringSerde(yourJson())
```

### Replacing Dependencies For Better Testability

When it comes to handling the time, no one wants to wait for 30 minutes for a scheduler job, or for a delayed task to be
able to test it.
In these situations what we need to do is `advancing` the time, or replacing the effect of the time for our needs. This
may require you to change your code, too. Because, we might need to provide a time-free implementation to an interface,
or we might need to extract it to an interface if not properly implemented.

For example, imagine we have a delayed command executor that accepts a task and a time for it
to delay it until it is right time to execute. But, in tests we need to replace this behaviour with the time-effect free
implementation.

```kotlin
class BackgroundCommandBusImpl // is the class for delayed operations
```

We would like to by-pass the time-bounded logic inside BackgroundCommandBusImpl, and for e2eTest scope we write:

```kotlin
class NoDelayBackgroundCommandBusImpl(
  backgroundMessageEnvelopeDispatcher: BackgroundMessageEnvelopeDispatcher,
  backgroundMessageEnvelopeStorage: BackgroundMessageEnvelopeStorage,
  lockProvider: CouchbaseLockProvider,
) : BackgroundCommandBusImpl(
  backgroundMessageEnvelopeDispatcher,
  backgroundMessageEnvelopeStorage,
  lockProvider
) {

  override suspend fun <TNotification : BackgroundNotification> publish(
    notification: TNotification,
    options: BackgroundOptions,
  ) {
    super.publish(notification, options.withDelay(0))
  }

  override suspend fun <TCommand : BackgroundCommand> send(
    command: TCommand,
    options: BackgroundOptions,
  ) {
    super.send(command, options.withDelay(0))
  }
}
```

Now, it is time to tell the e2e test system to use the NoDelay implementation.

This is done by registering test dependencies (see "Registering Test Dependencies" section below).

### Writing Your Own Custom System

Stove's pluggable architecture lets you create custom systems for any component or behavior specific to your application. This is useful for:

- Capturing domain events in memory
- Integrating with schedulers (db-scheduler, Quartz, etc.)
- Controlling time in tests
- Testing domain-specific behavior

Here's a simple example of a custom scheduler system:

```kotlin
fun Stove.withSchedulerSystem(): Stove {
  getOrRegister(SchedulerSystem(this))
  return this
}

fun Stove.scheduler(): SchedulerSystem = getOrNone<SchedulerSystem>().getOrElse {
  throw SystemNotRegisteredException(SchedulerSystem::class)
}

class SchedulerSystem(override val stove: Stove) : AfterRunAware<ApplicationContext>, PluggedSystem {

  private lateinit var scheduler: WaitingRoomScheduler
  private lateinit var backgroundCommandBus: BackgroundCommandBusImpl

  fun advance(): SchedulerSystem {
    scheduler.publishValidProducts()
    return this
  }

  fun advanceBackgroundCommandBus(): SchedulerSystem {
    backgroundCommandBus.dispatchTimeoutNotifications()
    return this
  }

  override suspend fun afterRun(context: ApplicationContext) {
    scheduler = context.getBean()
    backgroundCommandBus = context.getBean()
  }

  override fun close() {}
}
```

Later you can use it in testing:

```kotlin
stove {
  scheduler {
    advance()
  }
}
```

!!! tip "Comprehensive Guide"
    For a complete guide on writing custom systems including examples for db-scheduler integration, 
    in-memory event capture, and time control systems, see [Writing Custom Systems](writing-custom-systems.md).

## Accessing an application dependency with a system

As you can see, in the example above, if a system implements
`AfterRunAware<ApplicationContext>` then, `afterRun` method becomes available, in here we have access to applications
dependency container to resolve any bean we need to use.

```kotlin
override suspend fun afterRun(context: ApplicationContext) {
  scheduler = context.getBean()
  backgroundCommandBus = context.getBean()
}
```

### Registering Test Dependencies

You can add test-scoped beans to configure the Spring application from the test perspective using `addTestDependencies`:

**Spring Boot 2.x / 3.x:**

```kotlin
import com.trendyol.stove.addTestDependencies

runApplication<MyApp>(*params) {
    addTestDependencies {
        bean<YourInstanceToReplace>(isPrimary = true)
        bean<NoDelayBackgroundCommandBusImpl>(isPrimary = true)
    }
}
```

**Spring Boot 4.x:**

```kotlin
import com.trendyol.stove.addTestDependencies4x

runApplication<MyApp>(*params) {
    addTestDependencies4x {
        registerBean<YourInstanceToReplace>(primary = true)
        registerBean<NoDelayBackgroundCommandBusImpl>(primary = true)
    }
}
```

`addTestDependencies` / `addTestDependencies4x` are extensions that help us register our dependencies in the application.

```kotlin  hl_lines="4-7"
springBoot(
  runner = { parameters ->
    runApplication<MyApp>(*parameters) {
      addTestDependencies {
        bean<YourInstanceToReplace>(isPrimary = true)
        bean<NoDelayBackgroundCommandBusImpl>(isPrimary = true)
      }
    }
  },
  withParameters = listOf(
    "logging.level.root=error",
    "logging.level.org.springframework.web=error",
    "spring.profiles.active=default",
    "server.http2.enabled=false",
    "kafka.heartbeatInSeconds=2",
    "kafka.autoCreateTopics=true",
    "kafka.offset=earliest"
  )
)
```
