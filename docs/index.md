# What is Stove?

Stove is an end-to-end testing framework that spins up physical dependencies and your application all together. So you
have a control over
dependencies via code, not with the `yaml` files. Your e2e testing does not need to know anything about the infra. It is
**pluggable**.
Having said that, the only dependency is `docker` since Stove is
using [testcontainers](https://github.com/testcontainers/testcontainers-java) underlying.

You can use JUnit and Kotest for running the tests. You can run all the tests on your CI, too. But that needs **DinD(
docker-in-docker)** integration.

## How to build the source code?

- JDK 16+
- Docker for running the tests (please use the latest version)

```shell
./gradlew build # that will build and run the tests
```

## Where to start?

Address your dependencies that _application under test (in other words your API/Microservice/Application)_  uses. Your
application needs some physical dependencies
to be able to run properly on any environment. If Stove has those implementations you may proceed using it. These
dependencies:

- Couchbase
- Kafka

for now.

### [Testing Approaches](./testing-approaches)

You can start looking at the ways of testing an application with Stove. These are explained in detail under the
corresponding sections.

#### [1. Attaching to the application](testing-approaches/attaching)

#### [2. As Black-Box](testing-approaches/black-box)
