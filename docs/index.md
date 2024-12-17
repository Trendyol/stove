# Stove

Stove is an end-to-end testing framework that spins up physical dependencies and your application all together. So you
have a control over dependencies via Kotlin code.

In the JVM world, thanks to code interoperability, you application code and test can be written with different JVM
languages and can be run together.
For example, you can write your application code with Java and write your tests with Kotlin, or Application code with
Scala and test with Kotlin, etc.
Stove uses this ability and provides a way to write your tests in Kotlin.

Your tests will be infra agnostic, but component aware, so they can use easily necessary physical components with Stove
provided APIs.
All the infra is **pluggable**, and can be added easily. You can also create your own infra needs by using the
abstractions
that Stove provides.
Having said that, the only dependency is `docker` since Stove is
using [testcontainers](https://github.com/testcontainers/testcontainers-java) underlying.

You can use JUnit and Kotest for running the tests. You can run all the tests on your CI, too.
But that needs **DinD(docker-in-docker)** integration.

The medium story about the motivation behind the framework:
[A New Approach to the API End-to-End Testing in Kotlin](https://medium.com/trendyol-tech/a-new-approach-to-the-api-end-to-end-testing-in-kotlin-f743fd1901f5)

_Note: Stove is not a replacement for the unit tests, it is a framework for end-to-end/component tests._

## What is the problem?

In the JVM world, we have a lot of frameworks for the application code, but when it comes to integration/component/e2e
testing
we don't have a single framework that can be used for all the tech stacks. We have testcontainers but you still need to
do lots of plumbing to make it work with your tech stack.

The use-cases that led us develop the Stove are to increase the productivity of the developers while keeping the quality
of the codebase high and coherent.

Those use-cases are:

- Kotlin app with Spring-Boot
- Kotlin app with Ktor
- Java app with Spring-Boot
- Java app with Micronaut
- Java app with Quarkus
- Scala app with Spring-Boot

People have different tech stacks and each time when they want to write e2e tests, they need to write a lot of
boilerplate code.
Alongside the physical components that are needed to be started, we need to write the code to start the application, and
the code to access the beans of the application.
Stove is here to solve this problem. It provides a single API to write e2e tests for all the tech stacks.

**Stove unifies the testing experience whatever you use.**

## High Level Architecture

![img](./assets/stove_architecture.svg)

## How to build the source code?

- JDK 17+
- Docker for running the tests (please use the latest version)

```shell
./gradlew build # that will build and run the tests
```

## Getting Started

### Pre-requisites

- JDK 17+
- Docker for running the tests (please use the latest version)
- Kotlin 1.8+
- Gradle or Maven for running the tests, but Gradle is recommended.
    - Gradle is the default build tool for Stove, and it is used in the examples.
    - If you are using Intellij IDEA, Kotest plugin is recommended.

The framework still under development and is getting matured. It is working well and in use at Trendyol.
Besides, the Stove tests are highly likely going to be located under your testing context and the folder,
so, it is risk-free to apply and use, give it a try!

`$version = please check the current version`

Versions are available at [Releases](https://github.com/Trendyol/stove/releases)

!!! Tip

    You can use SNAPSHOT versions for the latest features. You can add the following repository to your build file.
    SNAPSHOT versions released with the `1.0.0.{buildNumber}-SNAPSHOT` strategy.
  
      ```kotlin
      repositories {
          maven {
              url = uri("https://oss.sonatype.org/content/repositories/snapshots/")
          }
      }
      ```

Every physical component that your testing needs is a separate module in Stove. You can add them according to your
needs.
Stove supports the following components:

- [Kafka](Components/02-kafka.md)
- [MongoDB](Components/07-mongodb.md)
- [MSSQL](Components/08-mssql.md)
- [PostgreSQL](Components/06-postgresql.md)
- [Redis](Components/09-redis.md)
- [Elasticsearch](Components/03-elasticsearch.md)
- [Couchbase](Components/01-couchbase.md)
- [Wiremock](Components/04-wiremock.md)
- [HTTP](Components/05-http.md)
- [Bridge](Components/10-bridge.md)

=== "Gradle"

    ```kotlin
    repositories {
      mavenCentral()
    }

    dependencies {
      // Application Under Test
      
      // Spring Boot
      testImplementation("com.trendyol:stove-spring-testing-e2e:$version")
      
      // or

      // Ktor
      testImplementation("com.trendyol:stove-ktor-testing-e2e:$version")
      
      // Components
      testImplementation("com.trendyol:stove-testing-e2e:$version")
      testImplementation("com.trendyol:stove-testing-e2e-kafka:$version")
      testImplementation("com.trendyol:stove-testing-e2e-mongodb:$version")
      testImplementation("com.trendyol:stove-testing-e2e-mssql:$version")
      testImplementation("com.trendyol:stove-testing-e2e-postgresql:$version")
      testImplementation("com.trendyol:stove-testing-e2e-redis:$version")
      testImplementation("com.trendyol:stove-testing-e2e-elasticsearch:$version")
      testImplementation("com.trendyol:stove-testing-e2e-couchbase:$version")
      testImplementation("com.trendyol:stove-testing-e2e-wiremock:$version")
      testImplementation("com.trendyol:stove-testing-e2e-http:$version")
    }
    ```

### How To Write Tests?

Stove uses your application entrance point to start your application alongside the physical components. The
application's `main` is the entrance point for the applications in general.

Everything starts with the `TestSystem` class. You can configure your system with the `with` function.

```kotlin
TestSystem()
  .with {
    // your configurations depending on the dependencies you need
  }.run()
```

`with` function is a lambda that you can configure your system. You can add your physical components.
It is also a place to plug your custom **systems** that you might want to create.
If you added `com.trendyol:stove-testing-e2e-kafka` package, you can use `kafka` function in the `with` block.

```kotlin
TestSystem()
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
    `com.trendyol.stove.testing.e2e.system.abstractions.ApplicationUnderTest` interface.
    You can implement this interface for your framework.

Let's create an example for a Spring-Boot application with Kafka and explain the setup flow.

The dependencies we will need in the `build.gradle.kts` file are:

```kotlin
 dependencies {
  testImplementation("com.trendyol:stove-testing-e2e:$version")
  testImplementation("com.trendyol:stove-testing-e2e-kafka:$version")
  testImplementation("com.trendyol:stove-testing-e2e-http:$version")
  testImplementation("com.trendyol:stove-spring-testing-e2e:$version")
}
```

```kotlin
TestSystem()
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
TestSystem.validate {
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
The reference `it` in this block is the physical component itself and it's exposed properties. Whenever Kafka and
testing suite start, the properties are changed and passed down to the application.

### `run` function

Runs the entire setup. It starts the physical components and the application.

!!! warning "Run the Setup Once"

    You should run the setup once in your test suite.
    You can run it in the `@BeforeAll` function of JUnit or implement `AbstractProjectConfig#beforeProject` in Kotest.
    Teardown is also important to call. You can run it in the `@AfterAll` function of JUnit or implement
    `AbstractProjectConfig#afterProject` in Kotest.
    Simply calling `TestSystem.stop()` is enough to stop the setup.

### Writing Tests

After the setup is done, you can write your tests. You can use the `validate` function to write your tests.

```kotlin
TestSystem.validate {
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
        testImplementation("com.trendyol:stove-spring-testing-e2e:$version")
        
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
    class Stove : AbstractProjectConfig() {    
        override suspend fun beforeProject(): Unit = 
            TestSystem()
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
    
        override suspend fun afterProject(): Unit = TestSystem.stop()
    }
    ```

=== "JUnit"

    ```kotlin
    class TestSystemConfig {
    
        @BeforeAll
        fun beforeProject() = runBlocking {
             TestSystem()
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
            TestSystem.stop()
        }
    }
    ```

In the section of `springBoot` function, we have configured the application's entry point, and the parameters that are
passed to the application. `stove.spring.example.run(parameters)` is the entrance point of the application.

Like the concept of `service under test` from the Test-Driven-Development.
Here we have the similar concept, since we're testing the entire system, it is called `Application Under Test`

In here we're configuring the Spring Boot application as _application under test_.

!!! note

    `server.port=8001` is a Spring config, TestSystem's `baseUrl` needs to match with it, since Http requests are made
     against the `baseUrl` that is defined. `withDefaultHttp` creates a WebClient and uses the `baseUrl` that is passed.

##### Writing Tests

Here is an example test that validates `http://localhost:$port/hello/index` returns the expected text
=== "Kotest"

    ```kotlin
    class ExampleTest: FunSpec({

        test("should return hi"){
            TestSystem.validate {
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
            TestSystem.validate {
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
    TestSystem.validate {
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
        testImplementation("com.trendyol:stove-ktor-testing-e2e:$version")
        
        // You can add other components if you need
    }
    ```

#### Example Setup

```kotlin
TestSystem()
  .with {
    // You can add other components if you need
    // We removed for simplicity

    ktor(
      withParameters = listOf(
        "port=8080"
      ),
      runner = { parameters ->
        stove.ktor.example.run(parameters) {
          addTestSystemDependencies()
        }
      }
    )
  }.run()
```

After you've added `stove-ktor-testing-e2e` dependency, and configured the application's `main` function for Stove to
enter,
it is time to run your application for the first time from the test-context with Stove.

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

=== "After"

    ```kotlin
    object ExampleApp {
      @JvmStatic
      fun main(args: Array<String>) {
         run(args)
      }
        
     fun run(args: Array<String>, 
            wait: Boolean = true, 
            configure: org.koin.core.module.Module = module { }
        ): Application {
         val config = loadConfiguration<Env>(args)
         return startKtorApplication(config, wait) {
             appModule(config, configure)
         }
      }
    }
    ```

As you can see from `before-after` sections, we have divided the application main function into two parts.
`run(args, wait, configure)` method is the important point for the testing configuration. `configure` allows us to
override any dependency from the testing side that is being `time` related or `configuration` related.

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

Stove works with multiple serializers and deserializers. The package `stove-testing-e2e` provides the following
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

Now, it is time to tell to e2eTest system to use NoDelay implementation.

That brings us to initializers.

### Writing Your Own TestSystem

```kotlin
fun TestSystem.withSchedulerSystem(): TestSystem {
  getOrRegister(SchedulerSystem(this))
  return this
}

fun TestSystem.scheduler(): SchedulerSystem = getOrNone<SchedulerSystem>().getOrElse {
  throw SystemNotRegisteredException(SchedulerSystem::class)
}

class SchedulerSystem(override val testSystem: TestSystem) : AfterRunAware<ApplicationContext>, PluggedSystem {

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

Later you can use it in testing;

```kotlin
validate {
  scheduler {
    advance()
  }
}
```

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

### Writing a TestInitializer

The tests initializers help you to add test scoped beans, basically you can configure the Spring application from the
test perspective.

```kotlin
class TestInitializer : BaseApplicationContextInitializer({
  bean<YourInstanceToReplace>(isPrimary = true)
  bean<NoDelayBackgroundCommandBusImpl>(isPrimary = true) // Optional dependency to alter delayed implementation with 0-wait.
})

fun SpringApplication.addTestDependencies() {
  this.addInitializers(TestInitializer())
}
```

`addTestDependencies` is an extension that helps us to register our dependencies in the application.

```kotlin  hl_lines="4"
.springBoot(
  runner = { parameters ->
    com.trendyol.exampleapp.run(parameters) {
      addTestDependencies()
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
