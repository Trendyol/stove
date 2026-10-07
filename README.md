<p align="center">
  <img src="docs/assets/stove-mark.svg" alt="Stove logo" width="96" height="96"/>
</p>

<h1 align="center">Stove</h1>

<p align="center">
  End-to-end tests in Kotlin for JVM and non-JVM applications.<br/>
  Start your app and its dependencies, make a request, then check what happened in the database and on the message bus.
</p>

<p align="center">
  <img src="https://img.shields.io/maven-central/v/com.trendyol/stove?versionPrefix=0&label=release&color=blue" alt="Release"/>
  <a href="https://github.com/Trendyol/homebrew-trendyol-tap"><img src="https://img.shields.io/github/v/release/Trendyol/stove?label=stove-server%20%28homebrew%29&amp;logo=homebrew&amp;color=FBB040" alt="Stove Server Homebrew release"/></a>
  <a href="https://github.com/Trendyol/stove/pkgs/container/stove-server"><img src="https://img.shields.io/github/v/release/Trendyol/stove?label=stove-server%20%28docker%29&amp;logo=docker&amp;color=2496ED" alt="Stove Server Docker release"/></a>
  <a href="https://central.sonatype.com/service/rest/repository/browse/maven-snapshots/com/trendyol/"><img src="https://img.shields.io/badge/dynamic/xml?url=https%3A%2F%2Fcentral.sonatype.com%2Frepository%2Fmaven-snapshots%2Fcom%2Ftrendyol%2Fstove%2Fmaven-metadata.xml&query=%2F%2Fmetadata%2Fversioning%2Flatest&label=SNAPSHOT%20%26%20stove-next%28homebrew%29&color=orange" alt="Snapshot"/></a>
  <a href="https://codecov.io/gh/Trendyol/stove"><img src="https://codecov.io/gh/Trendyol/stove/graph/badge.svg?token=HcKBT3chO7" alt="codecov"/></a>
  <a href="https://scorecard.dev/viewer/?uri=github.com/Trendyol/stove"><img src="https://img.shields.io/ossf-scorecard/github.com/Trendyol/stove?label=openssf%20scorecard&style=flat" alt="OpenSSF Scorecard"/></a>
</p>

<p align="center">
  <a href="https://trendyol.github.io/stove/getting-started/">Get started</a> ·
  <a href="https://trendyol.github.io/stove/">Documentation</a> ·
  <a href="https://trendyol.github.io/stove/recipes/">Recipes</a> ·
  <a href="https://trendyol.github.io/stove/dashboard-demo/">Try the dashboard</a> ·
  <a href="https://trendyol.github.io/stove/release-notes/">Release notes</a>
</p>

An order endpoint can return `201` and still fail to save the order or publish its event. Stove lets you check all three in the same test:

```kotlin
stove {
  http {
    postAndExpectBodilessResponse("/orders", body = CreateOrderRequest(userId, productId).some()) {
      it.status shouldBe 201
    }
  }

  postgresql {
    shouldQuery<Order>("SELECT status FROM orders WHERE user_id = '$userId'", mapper = { row ->
      Order(row.string("status"))
    }) {
      it.single().status shouldBe "CONFIRMED"
    }
  }

  kafka {
    shouldBePublished<OrderCreatedEvent> {
      actual.userId == userId
    }
  }
}
```

This example assumes the test has seeded a customer and product and configured the three systems. Request, row, and event types come from your application. The [Spring showcase recipe](recipes/jvm/kotlin-recipes/spring-showcase/) has a complete application and test suite you can run.

## Why Stove?

Testcontainers starts infrastructure. An end-to-end test also needs to pass connection details to the app, boot it, wait for asynchronous work, make assertions, and clean up. Stove handles that lifecycle and gives you Kotlin blocks for the systems involved.

- **Test through your app.** Drive HTTP, WebSocket, or gRPC calls, then inspect database state, Kafka messages, and calls to mocked services.
- **Debug the whole flow.** In-process JVM runners let you use breakpoints and access application beans. Failure reports collect operations and system snapshots; optional tracing shows the call chain.
- **Use the same test style across stacks.** Run Spring Boot, Ktor, Micronaut, or Quarkus in the test JVM; launch another language as a process or container; or connect to an app that is already running.

Stove is useful when the behavior crosses component boundaries. Your test framework still handles test discovery, assertions, and unit tests.

## Get started

Tests are written in **Kotlin**, even when the application is Java, Scala, Go, Python, or another language. Stove supports **JDK 17+**. You'll need **Docker** for dependencies started through Testcontainers; [provided instances](https://trendyol.github.io/stove/Components/11-provided-instances/) let you use infrastructure you already have.

### Run an existing example

The Spring showcase exercises HTTP, PostgreSQL, Kafka, WireMock, gRPC, and tracing. To run it from this repository, use **JDK 25** (the repository's Gradle runtime) and start Docker:

```bash
git clone https://github.com/Trendyol/stove.git
cd stove/recipes/jvm
./gradlew :kotlin-recipes:spring-showcase:e2eTest
```

The first run downloads dependencies and container images. Start with the recipe's [Stove configuration](recipes/jvm/kotlin-recipes/spring-showcase/src/test-e2e/kotlin/com/trendyol/stove/examples/kotlin/spring/e2e/setup/StoveConfig.kt) to see how connection details reach the application and how startup and teardown fit together.

### Add Stove to your application

Follow [Getting Started](https://trendyol.github.io/stove/getting-started/) for dependencies, test discovery, and a complete setup. Pick the runner that matches how you want to launch your app:

| Application | Setup guide |
|-------------|-------------|
| In the test JVM | [Spring Boot](https://trendyol.github.io/stove/frameworks/spring-boot/), [Ktor](https://trendyol.github.io/stove/frameworks/ktor/), [Micronaut](https://trendyol.github.io/stove/frameworks/micronaut/), [Quarkus](https://trendyol.github.io/stove/frameworks/quarkus/) |
| A separate process | [Polyglot testing](https://trendyol.github.io/stove/other-languages/) |
| A container | [Container runner](https://trendyol.github.io/stove/Components/22-container/) |
| Already running | [Provided application](https://trendyol.github.io/stove/Components/19-provided-application/) |

Register dependencies and one application runner in `Stove().with { ... }.run()` before the suite, and call `Stove.stop()` at teardown. For Kotest 6, configure project discovery in `kotest.properties` and register `StoveKotestExtension`; JUnit uses `StoveJUnitExtension`. Both attach Stove evidence to failed tests.

Keep the Stove BOM, test modules, tracing plugin, and dashboard server on matching versions. Kafka publish/consume assertions also need the [application-side interceptors](https://trendyol.github.io/stove/Components/02-kafka/); setting only the broker address is not enough.

## When a test fails

The console report includes the operations leading up to the failure, their inputs and outputs, and available system snapshots. Enable [tracing](https://trendyol.github.io/stove/Components/15-tracing/) to add the application call chain:

```text
EXECUTION TRACE (Call Chain)
✓ POST /orders
  ✓ OrderController.create
    ✓ OrderService.placeOrder
      ✓ SELECT inventory
      ✗ POST /payments/charge — PaymentTimeoutException
      ✓ orders.created publish
```

This illustrative trace points to the payment call behind a failed order assertion. See [When a Test Fails](https://trendyol.github.io/stove/observability/when-it-fails/) for the path from a console failure to the relevant trace, mock interaction, or database snapshot.

## Explore the dashboard

**[Open the interactive demo →](https://trendyol.github.io/stove/dashboard-demo/)**

Browse sample applications and historical runs, inspect a failing checkout, follow its trace, compare expected and actual values, and open Kafka, OIDC, and database snapshots. The demo uses the same dashboard as the Stove server. You can replay a test, try the SQL workbench, change retention, and reset the sample data. Everything stays in your browser.

To collect evidence from your own tests, install and start the server:

```bash
brew install trendyol/trendyol-tap/stove
stove
```

Then add `stove-dashboard` and register `dashboard { }` alongside your application's existing Stove setup. Open [localhost:4040](http://localhost:4040) and run your tests. Traces require the tracing module and its setup too.

The [Dashboard guide](https://trendyol.github.io/stove/Components/18-dashboard/) covers container installation, test configuration, shared PostgreSQL storage, and deployment. The server's admin tools can modify stored data and have no built-in authentication; keep your own server on a trusted network.

## Supported systems

Register only what your tests need. Each link covers dependencies, configuration, and assertions.

| Area | Modules |
|------|---------|
| Databases | [PostgreSQL](https://trendyol.github.io/stove/Components/06-postgresql/), [MySQL](https://trendyol.github.io/stove/Components/16-mysql/), [MSSQL](https://trendyol.github.io/stove/Components/08-mssql/), [MongoDB](https://trendyol.github.io/stove/Components/07-mongodb/), [Couchbase](https://trendyol.github.io/stove/Components/01-couchbase/), [Cassandra](https://trendyol.github.io/stove/Components/17-cassandra/) |
| Search and cache | [Elasticsearch](https://trendyol.github.io/stove/Components/03-elasticsearch/), [Redis](https://trendyol.github.io/stove/Components/09-redis/) |
| Messaging | [Kafka](https://trendyol.github.io/stove/Components/02-kafka/) |
| Clients | [HTTP and WebSockets](https://trendyol.github.io/stove/Components/05-http/), [gRPC](https://trendyol.github.io/stove/Components/12-grpc/) |
| Mocks | [WireMock](https://trendyol.github.io/stove/Components/04-wiremock/), [gRPC mock server](https://trendyol.github.io/stove/Components/14-grpc-mock/), [OIDC](https://trendyol.github.io/stove/Components/23-oidc/) |
| Diagnostics | [Reporting](https://trendyol.github.io/stove/Components/13-reporting/), [Tracing](https://trendyol.github.io/stove/Components/15-tracing/), [Dashboard](https://trendyol.github.io/stove/Components/18-dashboard/) |

Need something else? [Write a custom system](https://trendyol.github.io/stove/writing-custom-systems/) to give it the same lifecycle and test DSL.

## Working with coding agents

The server exposes a read-only [MCP endpoint](https://trendyol.github.io/stove/Components/21-mcp/) at `http://localhost:4040/mcp`. An agent can inspect failed runs, trace spans, and system evidence using the same run and test IDs as the dashboard. Console reports and logs remain available without MCP.

Run `stove skills install` in your repository to install the [Stove agent skill](.agents/skills/stove/). It covers setup, assertions, and failure investigation. See the [MCP guide](https://trendyol.github.io/stove/Components/21-mcp/) for configuration and usage.

## Common questions

**Does Stove replace Testcontainers?**
Stove uses Testcontainers to manage dependency containers and adds application startup, configuration, assertions, and diagnostics around them.

**Can I reuse containers locally?**
Yes: `Stove { keepDependenciesRunning() }` keeps reusable dependency containers running between suites. This reduces container startup work; the application and tests still need to start.

**When does cleanup run?**
A system's `cleanup` callback runs when Stove stops at suite teardown. It does not reset state between individual tests. [Migrations](https://trendyol.github.io/stove/Components/06-postgresql/#migrations) run during system startup.

**Can tests run in parallel?**
Yes, when their state and assertions are isolated. Use distinct IDs and filter queries and event assertions by those IDs. Shared tables, topics, mock stubs, and application state can still cause interference; use separate schemas, resources, or suites where needed.

## Community

Used at [Trendyol](https://www.trendyol.com). Using Stove elsewhere? Open a PR to add your company.

[Issues](https://github.com/Trendyol/stove/issues) and contributions are welcome. See [CONTRIBUTING.md](CONTRIBUTING.md) for local checks and CI, or explore the [recipes](recipes/) for more examples.

- [Background and design](https://medium.com/trendyol-tech/a-new-approach-to-the-api-end-to-end-testing-in-kotlin-f743fd1901f5)
- [Video walkthrough (Turkish)](https://youtu.be/DJ0CI5cBanc?t=669)
- [Release notes and migration guides](https://trendyol.github.io/stove/release-notes/)

Licensed under [Apache 2.0](LICENSE). APIs are evolving; check the release notes when upgrading.
