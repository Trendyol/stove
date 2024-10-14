# What is Stove?

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

!!! Note
    Some people tend to call these tests as _integration tests_, some call them as _component tests_, some call them as
    end-to-end tests. In this documentation, we will use the term end-to-end tests.
    We think that the **e2e/component tests** term is more serving to the purpose of message we want to convey.

## What is the problem?

In the JVM world, we have a lot of frameworks for the application code, but we don't have a mature framework for
end-to-end/component testing.
The use-cases that led us develop the Stove are to increase the productivity of the developers while keeping the quality
of the codebase high and coherent.

Those use-cases are:
- Kotlin app with Spring-Boot
- Kotlin app with Ktor
- Java app with Spring-Boot
- Java app with Micronaut
- Java app with Quarkus
- Scala app with Spring-Boot

People have different tech stacks and each time when they want to write e2e tests, they need to write a lot of boilerplate code.
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

The framework still under development and is getting matured. In general, it is working well and in use at Trendyol.
The Stove tests are highly likely going to be located under your testing context and the folder,
so, it is risk-free to apply and use, give it a try!

`$version = please check the current version`

Versions are available at [Releases](https://github.com/Trendyol/stove/releases)

Every physical component that your testing needs is a separate module in Stove. You can add them according to your
needs.
Stove supports the following components:

- [Kafka](Components/02-kafka/)
- [MongoDB](Components/07-mongodb/)
- [MSSQL](Components/08-mssql/)
- [PostgreSQL](Components/06-postgresql/)
- [Redis](Components/10-redis/)
- [Elasticsearch](Components/03-elasticsearch/)
- [Couchbase](Components/01-couchbase/)
- [Wiremock](Components/04-wiremock/)
- [HTTP](Components/05-http/)

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
application's main is the entrance point for the applications in general.

Everything starts with the `TestSystem` class. You can configure your system with the `with` function.

```kotlin
TestSystem()
  .with {
      // your configurations depending on the dependencies you need
  }
```

`with` function is a lambda that you can configure your system. You can add your physical components. 
It is also a place for your custom **systems** that you can create. If you added `com.trendyol:stove-testing-e2e-kafka`
package, you can use `kafka` function in the `with` block.

```kotlin
TestSystem()
  .with {
      kafka {
          // your kafka configurations
      }
  }
```

!!! Note
    You can add multiple physical components in the `with` block. Think of it as a DSL for your test system and a `docker-compose` in Kotlin.


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
    If you want to add a new framework, you can check the `com.trendyol.stove.testing.e2e.system.abstractions.ApplicationUnderTest` interface. 
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
        objectMapper = ObjectMapperConfig.createObjectMapperWithDefaults(),
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
The typical setup for a Spring Boot application with Kafka is like this. You can see that we have a `httpClient` function that is used for the HTTP client against the application's endpoints. 
Then we have a `kafka` function that is used for the Kafka setup. 
Then we have a `bridge` function that is used for accessing the DI container of the application.
Then we have a `springBoot` function that is used for the Spring Boot application setup.

#### `httpClient` function

It is used for the HTTP client against the application's endpoints. You can configure the base URL of the application. When the application is started, the base URL is used for the HTTP client.

#### `kafka` function

It is used for the Kafka setup. You can configure the Kafka container options and the Kafka properties. When the application is started, the Kafka container is started and the Kafka properties are used for the application.
We will investigate the Kafka setup in detail in the [Kafka](/Components/02-kafka/) section.

#### `bridge` function

This function is used for accessing the DI container of the application. When the application is started, the bridge is created and the DI container is accessed in the tests.

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

This function is used for the Spring Boot application setup. You can configure the runner function and the parameters of the application.
When the application is started, the runner function is called with the parameters. 
The parameters you see in `runner` function are the parameters that are passed to the Spring Boot application when it is started.
Each physical component exposes its own properties and you can use them in the application.
Here:
```kotlin
kafka {
  KafkaSystemOptions(
    objectMapper = ObjectMapperConfig.createObjectMapperWithDefaults(),
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
The reference `it` in this block is the physical component itself and it's exposed properties. Whenever Kafka and testing suite start, the properties are changed and passed down to the application.

### `run` function

Runs the entire setup. It starts the physical components and the application.


!!! warning "Run the Setup Once"
    You should run the setup once in your test suite. 
    You can run it in the `@BeforeAll` function of JUnit or implement `AbstractProjectConfig#beforeProject` in Kotest.
    Teardown is also important to call. You can run it in the `@AfterAll` function of JUnit or implement `AbstractProjectConfig#afterProject` in Kotest.
    Simply calling `TestSystem.stop()` is enough to stop the setup.
