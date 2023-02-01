# What is Stove?

Stove is an end-to-end testing framework that spins up physical dependencies and your application all together. So you have a control over
dependencies via code, not with the `yaml` files. Your e2e testing does not need to know anything about the infra. It is **pluggable**.
Having said that, the only dependency is `docker` since Stove is using [testcontainers](https://github.com/testcontainers/testcontainers-java) underlying.

You can use JUnit and Kotest for running the tests. You can run all the tests on your CI, too. But that needs **DinD(docker-in-docker)** integration.


## How to build the source code?

JDK 16+ is necessary.

```shell
./gradlew build # that will build and run the tests
```

## Where to start?

Address your dependencies that _application under test (in other words your API/Microservice/Application)_  uses. Your application needs some physical dependencies
to be able to run properly on any environment. If Stove has those implementations you may proceed using it. These dependencies:

- Couchbase
- Kafka

for now.

### Ways of testing?

With Stove there are two ways of testing. If you are using Spring framework for your application, you may attach Stove
to your application, so that you can debug and look with a magnifying glass to your code base. This is especially helpful
when one wants to see how code works when the physical dependencies are present. Because, unit and integration tests stay short
for the deeper insights when it comes to the behaviour of the system on production.

#### 1. Attaching to the application

There are entry point for every application, usually a `main` method that is invoked, and starts the application lifecycle.
If you are publishing your application/api/microservice as a docker image, `docker run ...` basically runs your application
highly likely with a `jvm/java` command.

In this approach, we're attaching Stove to very entrance of the application, in other words to its `main` function.

Stove calls your application's `main` function like you would call `java yourApplicationName.jar` to run the application from the test context.
So the runner is JUnit or Kotest.

For Stove to attach properly to your application, application's main function needs to allow that. This does not change
behaviour at all, it just opens a door for e2e testing framework to _enter_. We will discuss this later.

#### 2. As Black-Box

In this approach, your application does not have any part that is exposed to Stove, and Stove will not attach to application.
You could provide a docker image of your application, Stove will spin up the application and its dependencies.

**This is on the roadmap, and not implemented yet.**
