# JVM Frameworks

Same Stove. Same DSL. Different runner. Pick the starter that matches your app's framework. Every other moving part stays identical.

<div class="stove-tldr" markdown>
<span class="stove-tldr-title">What changes between starters</span>
Only the **runner block** (how Stove boots your app). Component config, test DSL, observability. All identical across Spring · Ktor · Micronaut · Quarkus.
</div>

## The four starters

<div class="stove-catalog">

  <div class="stove-sys-card">
    <div class="stove-sys-card-head"><strong>Spring Boot</strong><span class="stove-sys-card-badge">Bridge ✓</span></div>
    <p class="stove-sys-card-desc"><code>runApplication(...)</code> wrapped in <code>run(args)</code>. First-class Stove integration.</p>
    <div class="stove-sys-card-actions">
      <a href="spring-boot/">Guide</a>
      <a href="https://github.com/Trendyol/stove/tree/main/examples/spring-example">Example</a>
      <a class="open-in-wizard" data-fw="spring-boot">→ wizard</a>
    </div>
  </div>

  <div class="stove-sys-card">
    <div class="stove-sys-card-head"><strong>Ktor</strong><span class="stove-sys-card-badge">Bridge ✓</span></div>
    <p class="stove-sys-card-desc"><code>embeddedServer(...)</code> wrapped in <code>run(args)</code>. Koin or Ktor-DI.</p>
    <div class="stove-sys-card-actions">
      <a href="ktor/">Guide</a>
      <a href="https://github.com/Trendyol/stove/tree/main/examples/ktor-example">Example</a>
      <a class="open-in-wizard" data-fw="ktor">→ wizard</a>
    </div>
  </div>

  <div class="stove-sys-card">
    <div class="stove-sys-card-head"><strong>Micronaut</strong><span class="stove-sys-card-badge">Bridge ✓</span></div>
    <p class="stove-sys-card-desc"><code>ApplicationContext</code> startup wrapped in <code>run(args)</code>.</p>
    <div class="stove-sys-card-actions">
      <a href="micronaut/">Guide</a>
      <a href="https://github.com/Trendyol/stove/tree/main/examples/micronaut-example">Example</a>
      <a class="open-in-wizard" data-fw="micronaut">→ wizard</a>
    </div>
  </div>

  <div class="stove-sys-card">
    <div class="stove-sys-card-head"><strong>Quarkus</strong><span class="stove-sys-card-badge">Bridge ✗</span></div>
    <p class="stove-sys-card-desc"><code>@QuarkusMain</code> plus <code>Quarkus.run(*args)</code>. Needs readiness signal.</p>
    <div class="stove-sys-card-actions">
      <a href="quarkus/">Guide</a>
      <a href="https://github.com/Trendyol/stove/tree/main/examples/quarkus-example">Example</a>
      <a class="open-in-wizard" data-fw="quarkus">→ wizard</a>
    </div>
  </div>

</div>

## How the runner block differs

<div class="stove-compare" markdown="0">
  <div>
    <h4>Spring Boot</h4>

```kotlin
springBoot(
  runner = { params -> com.app.run(params) },
  withParameters = listOf("server.port=8080")
)
```

  </div>
  <div>
    <h4>Ktor</h4>

```kotlin
ktor(
  runner = { params -> com.app.run(params, wait = false) },
  withParameters = listOf("server.port=8080")
)
```

  </div>
</div>

<div class="stove-compare" markdown="0">
  <div>
    <h4>Micronaut</h4>

```kotlin
micronaut(
  runner = { params -> com.app.run(params) },
  withParameters = listOf("micronaut.server.port=8080")
)
```

  </div>
  <div>
    <h4>Quarkus</h4>

```kotlin
quarkus(
  runner = { params -> com.app.main(params) },
  withParameters = listOf("quarkus.http.port=8080")
)
```

  </div>
</div>

Everything around it. `httpClient`, `postgresql`, `kafka`, `wiremock`, `tracing`. Looks the same.

## Bridge availability

| Framework | Bridge | Why |
|---|---|---|
| Spring Boot | ✓ | `ApplicationContext` access wired in |
| Ktor | ✓ | Koin or Ktor-DI container exposed |
| Micronaut | ✓ | `ApplicationContext` access wired in |
| Quarkus | ✗ | CDI lifecycle integration not yet shipped |

For Quarkus, drive verification through HTTP, DB queries, and Kafka assertions instead of `using<T> { ... }`.

## What stays identical

- `Stove()` lifecycle, `with { }` registration
- every system module (`stove-postgres`, `stove-kafka`, `stove-wiremock`, ...)
- the test DSL: `stove { http { } postgresql { } kafka { } }`
- [reporting](../Components/13-reporting.md), [tracing](../Components/15-tracing.md), [dashboard](../Components/18-dashboard.md), [MCP](../Components/21-mcp.md)
- [recipes](../recipes/index.md). The same flow swaps starter freely

## Where to next

<div class="grid cards" markdown>

-   :material-magic-staff: **Wizard scaffolds the runner for you**. [Open wizard](../wizard.md)

-   :material-book-multiple: **End-to-end scenarios per stack**. [Recipes](../recipes/index.md)

-   :material-language-go: **Not on the JVM?**. [Polyglot setups](../other-languages/index.md)

</div>
