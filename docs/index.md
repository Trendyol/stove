---
hide:
  - navigation
  - toc
---

<div class="stove-hero" markdown="0">
  <div class="stove-hero-text">
    <span class="stove-hero-kicker">End-to-end testing against the runtime</span>
    <h1>Boot the app.<br/>Verify the flow.</h1>
    <p>Stove starts the application under test, wires the dependencies it talks to, and lets you assert the complete flow in one Kotlin DSL. Postgres, Kafka, Redis, gRPC, WireMock, tracing, and more. JVM-first. Polyglot-ready.</p>
    <div class="stove-hero-actions">
      <a class="stove-btn primary" href="#setup-wizard">✨ Launch the wizard</a>
      <a class="stove-btn" href="getting-started/">Getting started</a>
      <a class="stove-btn" href="recipes/">Recipes</a>
      <a class="stove-btn" href="https://github.com/Trendyol/stove">GitHub →</a>
    </div>
  </div>
  <div class="stove-term">
<span class="stove-term-body"><span class="c">// boot real app + real deps, assert real behavior</span>
<span class="f">stove</span> {
  <span class="f">http</span> {
    <span class="f">post</span>&lt;<span class="f">OrderResponse</span>&gt;(<span class="s">"/orders"</span>, body) {
      <span class="f">it</span>.<span class="f">status</span> <span class="k">shouldBe</span> <span class="n">201</span>
    }
  }
  <span class="f">postgresql</span> {
    <span class="f">shouldQuery</span>&lt;<span class="f">OrderRow</span>&gt;(
      query = <span class="s">"SELECT * FROM orders"</span>,
      mapper = { row -> <span class="f">OrderRow</span>(row.string(<span class="s">"id"</span>)) }
    ) {
      <span class="f">it</span>.<span class="f">size</span> <span class="k">shouldBe</span> <span class="n">1</span>
    }
  }
  <span class="f">kafka</span> {
    <span class="f">shouldBePublished</span>&lt;<span class="f">OrderCreated</span>&gt; {
      <span class="f">actual</span>.<span class="f">userId</span> == <span class="f">userId</span>
    }
  }
}<span class="blink"></span></span></div>
</div>

<div class="stove-tldr" markdown>
<span class="stove-tldr-title">In 30 seconds</span>
Stove registers the systems your app depends on, then registers one AUT runner. A system is a dependency, client, mock, or observability module such as HTTP, PostgreSQL, Kafka, WireMock, tracing, or dashboard. An AUT runner starts the app through a framework, process, or container runner, or targets an already-running app with `providedApplication()`. For framework/process/container runners, Stove passes generated connection details before the app starts; provided applications must already be configured externally. When a test fails, the failure report adds a timeline and system snapshots to the stack trace.
</div>

## What you actually get

<div class="stove-ribbon" markdown="0">
  <div class="stove-ribbon-item">
    <div class="icon">🧱</div>
    <strong>Real dependencies</strong>
    <p>Testcontainers by default, or provided instances when existing infrastructure should be reused.</p>
  </div>
  <div class="stove-ribbon-item">
    <div class="icon">🎛️</div>
    <strong>One DSL, many systems</strong>
    <p>The same <code>stove { }</code> shape works across Postgres, Kafka, gRPC, WireMock, Redis, MongoDB, Elasticsearch, and custom systems.</p>
  </div>
  <div class="stove-ribbon-item">
    <div class="icon">🌐</div>
    <strong>Polyglot AUT</strong>
    <p>Spring, Ktor, Micronaut, and Quarkus runners, plus Go, Python, Rust, Node, and other apps through process or container mode.</p>
  </div>
  <div class="stove-ribbon-item">
    <div class="icon">🛰️</div>
    <strong>Failures with context</strong>
    <p>Reporting is built in; tracing adds span trees, dashboard stores run evidence, and MCP exposes agent-readable evidence when enabled.</p>
  </div>
</div>

## <a id="setup-wizard"></a>Setup wizard

Pick runtime, framework, systems, mocks, and observability. The wizard composes version-aligned Gradle dependencies, `StoveConfig.kt`, and a sample test that exercises the selected systems. State syncs to the URL; share or bookmark presets.

<div id="stove-wizard" markdown="0">
  <noscript>
    <div class="admonition warning">
      <p class="admonition-title">JavaScript required</p>
      <p>Wizard needs JavaScript. Follow <a href="getting-started/">Getting Started</a> for manual setup.</p>
    </div>
  </noscript>
</div>

## Why Stove exists

JVM frameworks solve application startup well, but e2e suites still need the same surrounding mechanics in every service: dependency lifecycle, dynamic ports, runtime configuration, application boot, cleanup, and diagnostics.

<div class="stove-compare" markdown="0">
  <div>
    <h4>Without Stove</h4>
    <ul>
      <li>Hand-rolled Testcontainers setup per service</li>
      <li>Mocks where they shouldn't be (the bug hides there)</li>
      <li>Framework-specific test harness rewritten each time</li>
      <li>Stack traces without the system timeline</li>
      <li>Separate test harnesses for polyglot services</li>
    </ul>
  </div>
  <div>
    <h4>With Stove</h4>
    <ul>
      <li>Declarative <code>Stove().with { ... }</code> wiring</li>
      <li>Real DB, real broker, and external services mocked only at the network boundary</li>
      <li>One DSL across Spring · Ktor · Micronaut · Quarkus</li>
      <li>Failures include timeline and snapshots; tracing, dashboard, and MCP add deeper evidence when enabled</li>
      <li>Go/Python/Rust apps drop in via process/container mode</li>
    </ul>
  </div>
</div>

<span data-rn="highlight" data-rn-color="#00968855" data-rn-duration="800">One testing model. Many stacks.</span>

## Architecture

![Stove architecture](./assets/stove_architecture.svg)

## Building from source

```shell
# requires JDK 17+ and Docker
./gradlew build
```

Background and motivation: original [Medium article](https://medium.com/trendyol-tech/a-new-approach-to-the-api-end-to-end-testing-in-kotlin-f743fd1901f5).
