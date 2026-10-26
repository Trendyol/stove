<p align="center">
  <img src="docs/assets/stove-mark.svg" alt="Stove logo" width="96" height="96"/>
</p>

<h1 align="center">Stove</h1>

<p align="center">
  End-to-end tests in Kotlin for JVM and non-JVM applications.<br/>
  Run your application with its dependencies, test complete use cases, and see what happened when they fail.
</p>

<p align="center">
  <img src="https://img.shields.io/maven-central/v/com.trendyol/stove?label=release" alt="Latest release version on Maven Central"/>
  <a href="https://github.com/Trendyol/homebrew-trendyol-tap"><img src="https://img.shields.io/github/v/release/Trendyol/stove?label=stove-server%20%28homebrew%29&amp;logo=homebrew&amp;color=FBB040" alt="Stove Server Homebrew release"/></a>
  <a href="https://github.com/Trendyol/stove/pkgs/container/stove-server"><img src="https://img.shields.io/github/v/release/Trendyol/stove?label=stove-server%20%28docker%29&amp;logo=docker&amp;color=2496ED" alt="Stove Server Docker release"/></a>
  <a href="https://central.sonatype.com/service/rest/repository/browse/maven-snapshots/com/trendyol/"><img src="https://img.shields.io/badge/dynamic/xml?url=https%3A%2F%2Fcentral.sonatype.com%2Frepository%2Fmaven-snapshots%2Fcom%2Ftrendyol%2Fstove%2Fmaven-metadata.xml&query=%2F%2Fmetadata%2Fversioning%2Flatest&label=SNAPSHOT%20%26%20stove-server-next%20%28homebrew%29&color=orange" alt="Snapshot"/></a>
  <a href="https://codecov.io/gh/Trendyol/stove"><img src="https://codecov.io/gh/Trendyol/stove/graph/badge.svg?token=HcKBT3chO7" alt="codecov"/></a>
  <a href="https://scorecard.dev/viewer/?uri=github.com/Trendyol/stove"><img src="https://img.shields.io/ossf-scorecard/github.com/Trendyol/stove?label=openssf%20scorecard&style=flat" alt="OpenSSF Scorecard"/></a>
</p>

<p align="center">
  <a href="https://trendyol.github.io/stove/getting-started/">Get started</a> ·
  <a href="https://trendyol.github.io/stove/wizard/">Setup wizard</a> ·
  <a href="https://trendyol.github.io/stove/">Documentation</a> ·
  <a href="https://trendyol.github.io/stove/recipes/">Recipes</a> ·
  <a href="https://trendyol.github.io/stove/dashboard-demo/">Try the dashboard</a> ·
  <a href="https://trendyol.github.io/stove/release-notes/">Release notes</a>
</p>

## Why Stove?

An end-to-end test suite can become a small framework of its own. There is code to boot the application, connect its dependencies, prepare data, wait for background work, and explain what went wrong. Start another service or switch application frameworks, and much of that work starts over.

Stove exists to make that work reusable. It brings application startup, dependency configuration, assertions, and diagnostics into one test lifecycle. You describe the environment your application needs, then write tests around its use cases.

That same approach works across Spring Boot, Ktor, Micronaut, and Quarkus, and extends to applications in other languages. Your test suite can keep a familiar shape as the stack around it changes.

<p align="center">
  <img src="docs/assets/stove_new.svg" alt="A Stove test drives the application under test and its dependencies through one lifecycle" width="100%"/>
</p>

## What a Stove test looks like

Call your application, then check its database and messages in the same test:

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

The HTTP, PostgreSQL, and Kafka blocks give the test direct access to each part of the flow. Add the systems your application uses, including mocks for external services you want to control.

For JVM applications running in the test process, you can set a breakpoint in your application code and step through the request from the test. You can also access application beans when a test needs them. Applications running as a separate process, in a container, or in an existing environment use the same style of assertions.

## Get started

Stove is distributed as a set of libraries on Maven Central, consumed with the `com.trendyol:stove-bom` BOM.

Tests are written in **Kotlin**, even when the application is Java, Scala, Go, Python, or another language. Stove supports **JDK 17+** as a consumer; the repository's own build uses newer JDKs. You'll need **Docker** for dependencies started through Testcontainers; [provided instances](https://trendyol.github.io/stove/Components/11-provided-instances/) let you use infrastructure you already have.

### Add Stove to your application

Replace `x.y.z` with the latest release from the [Releases page](https://github.com/Trendyol/stove/releases) or the badge above.

```kotlin
// build.gradle.kts
val stoveVersion = "x.y.z"

dependencies {
  testImplementation(platform("com.trendyol:stove-bom:$stoveVersion"))
  testImplementation("com.trendyol:stove")
  testImplementation("com.trendyol:stove-spring")            // or -ktor / -micronaut / -quarkus
  testImplementation("com.trendyol:stove-extensions-kotest") // or -extensions-junit
  testImplementation("com.trendyol:stove-http")
  testImplementation("com.trendyol:stove-postgres")
  testImplementation("com.trendyol:stove-kafka")
}
```

Keep the BOM, every `com.trendyol:stove-*`, and `stove-server` (if you use the dashboard) on the same version. The [Getting Started guide](https://trendyol.github.io/stove/getting-started/) walks through a complete setup with Kotest or JUnit. Choose how you want to run your app:

| Application | Setup guide |
|-------------|-------------|
| In the test JVM | [Spring Boot](https://trendyol.github.io/stove/frameworks/spring-boot/), [Ktor](https://trendyol.github.io/stove/frameworks/ktor/), [Micronaut](https://trendyol.github.io/stove/frameworks/micronaut/), [Quarkus](https://trendyol.github.io/stove/frameworks/quarkus/) |
| A separate process | [Polyglot testing](https://trendyol.github.io/stove/other-languages/) |
| A container | [Container runner](https://trendyol.github.io/stove/Components/22-container/) |
| Already running | [Provided application](https://trendyol.github.io/stove/Components/19-provided-application/) |

### Run the Spring showcase

Try a complete setup with the [Spring showcase](recipes/jvm/kotlin-recipes/spring-showcase/). With **JDK 25** and Docker running:

```bash
git clone https://github.com/Trendyol/stove.git
cd stove/recipes/jvm
./gradlew :kotlin-recipes:spring-showcase:e2eTest
```

The first run downloads dependencies and container images. See the [Stove configuration](recipes/jvm/kotlin-recipes/spring-showcase/src/test-e2e/kotlin/com/trendyol/stove/examples/kotlin/spring/e2e/setup/StoveConfig.kt) for the complete test environment.

## When a test fails

Stove keeps the evidence from a failed test together in the console report: operations, inputs, outputs, and system snapshots. With [tracing](https://trendyol.github.io/stove/Components/15-tracing/) enabled, you can follow the request into your application:

```text
EXECUTION TRACE (Call Chain)
✓ POST /orders
  ✓ OrderController.create
    ✓ OrderService.placeOrder
      ✓ SELECT inventory
      ✗ POST /payments/charge — PaymentTimeoutException
      ✓ orders.created publish
```

Here, the payment call is where the investigation starts. From there, inspect the mock response, the database state, or the surrounding trace. [When a Test Fails](https://trendyol.github.io/stove/observability/when-it-fails/) walks through that process.

## Dashboard and Stove Server

**[Try the interactive demo →](https://trendyol.github.io/stove/dashboard-demo/)** (no install, everything stays in your browser)

Browse sample applications and historical runs, inspect a failing checkout, follow its trace, compare expected and actual values, and open Kafka, OIDC, and database snapshots. The demo uses the same dashboard as Stove Server. You can replay a test, try the SQL workbench, change retention, and reset the sample data.

For your own tests, **Stove Server** collects this evidence and keeps it available after the run. It is a separate, optional binary — not part of the `com.trendyol:stove-*` libraries — distributed via [Homebrew](https://github.com/Trendyol/homebrew-trendyol-tap) and [Docker](https://github.com/Trendyol/stove/pkgs/container/stove-server), and only needed if you want the dashboard, tracing storage, and MCP tools. Use it locally while developing or share a server with your team and CI jobs; see the [Dashboard guide](https://trendyol.github.io/stove/Components/18-dashboard/) for installation.

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

The evidence in the dashboard is also available to coding agents through the server's read-only [MCP tools](https://trendyol.github.io/stove/Components/21-mcp/). An agent can inspect a failed run, follow its trace, and look at the recorded system state while helping you investigate.

Run `stove skills install --agent claude --configure-mcp` in your repository to install the [Stove agent skill](.agents/skills/stove/) and configure its MCP connection. Choose `codex`, `claude`, `cursor`, or `opencode`; use `--dry-run` to preview or `--global` for all projects. The existing `stove skills install` command still installs the shared project skill. See [agent setup and plugin installation](https://trendyol.github.io/stove/agent-setup/) and the [MCP guide](https://trendyol.github.io/stove/Components/21-mcp/).

## Common questions

### Does Stove replace Testcontainers?

No. Stove uses Testcontainers to manage dependency containers and adds application startup, configuration, assertions, and diagnostics around them.

### Can I connect to infrastructure I already have?

Yes. The databases, Redis, Elasticsearch, and Kafka support [provided instances](https://trendyol.github.io/stove/Components/11-provided-instances/) through `.provided(...)` factories. Swap `SystemOptions(...)` for `SystemOptions.provided(...)` and supply connection details; Stove connects to existing infrastructure (a shared CI database, dev cluster, or staging broker) instead of starting a container. The DSL and assertions stay the same, but Stove does not create, isolate, pause, or destroy the dependency — you own its lifecycle.

### Can I reuse containers locally?

Yes: `Stove { keepDependenciesRunning() }` keeps reusable dependency containers running between suites. This reduces container startup work; the application and tests still need to start.

### When does cleanup run?

A system's `cleanup` callback runs when Stove stops at suite teardown. It does not reset state between individual tests. [Migrations](https://trendyol.github.io/stove/Components/06-postgresql/#migrations) run during system startup.

### Can tests run in parallel?

Yes, when their state and assertions are isolated. Use distinct IDs and filter queries and event assertions by those IDs. Shared tables, topics, mock stubs, and application state can still cause interference; use separate schemas, resources, or suites where needed.

## Community

Used at [Trendyol](https://www.trendyol.com). Using Stove elsewhere? Open a PR to add your company.

[Issues](https://github.com/Trendyol/stove/issues) and contributions are welcome. See [CONTRIBUTING.md](CONTRIBUTING.md) for local checks and CI, or explore the [example recipes in this repository](recipes/) for more working setups.

- [Background and design](https://medium.com/trendyol-tech/a-new-approach-to-the-api-end-to-end-testing-in-kotlin-f743fd1901f5)
- [Video walkthrough (Turkish)](https://youtu.be/DJ0CI5cBanc?t=669)
- [Release notes and migration guides](https://trendyol.github.io/stove/release-notes/)

Licensed under [Apache 2.0](LICENSE). APIs are evolving; check the release notes when upgrading.
