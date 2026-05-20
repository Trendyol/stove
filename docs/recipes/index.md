# Recipes

End-to-end scenarios that combine multiple systems. Each recipe is a complete, copy-paste setup. Gradle deps, `StoveConfig.kt`, and a runnable test. For a real-world flow.

Recipes complement the [Setup Wizard](../index.md) (which composes systems) and the [Components reference](../Components/index.md) (which documents each system in isolation).

<div class="stove-tldr" markdown>
<span class="stove-tldr-title">Working source on GitHub</span>
Every recipe on this page is a tightened walkthrough of a project in the Stove repo's <a href="https://github.com/Trendyol/stove/tree/main/recipes" target="_blank"><code>recipes/</code></a> folder. Clone, open in your IDE, run <code>./gradlew e2eTest</code>.
</div>

## Available recipes

<div class="grid cards" markdown>

-   :material-cart-check: **Order placement flow**

    HTTP POST → Postgres write → WireMock external call → Kafka event published.

    [Walkthrough](order-flow.md) · [source ↗](https://github.com/Trendyol/stove/tree/main/recipes/jvm/kotlin-recipes/spring-showcase)

</div>

## Browse the source

Don't see your scenario? Jump straight to the working projects.

<div class="stove-catalog">

  <div class="stove-sys-card">
    <div class="stove-sys-card-head"><strong>JVM · Kotlin</strong><span class="stove-sys-card-badge">3 recipes</span></div>
    <p class="stove-sys-card-desc">Spring showcase (HTTP + Postgres + Kafka + WireMock + tracing), Ktor + Postgres, Ktor + Mongo.</p>
    <div class="stove-sys-card-actions">
      <a href="https://github.com/Trendyol/stove/tree/main/recipes/jvm/kotlin-recipes" target="_blank">recipes/jvm/kotlin-recipes ↗</a>
    </div>
  </div>

  <div class="stove-sys-card">
    <div class="stove-sys-card-head"><strong>JVM · Java</strong><span class="stove-sys-card-badge">2 recipes</span></div>
    <p class="stove-sys-card-desc">Spring Boot + Postgres, Quarkus basic. e2e tests written in Kotlin against a Java AUT.</p>
    <div class="stove-sys-card-actions">
      <a href="https://github.com/Trendyol/stove/tree/main/recipes/jvm/java-recipes" target="_blank">recipes/jvm/java-recipes ↗</a>
    </div>
  </div>

  <div class="stove-sys-card">
    <div class="stove-sys-card-head"><strong>JVM · Scala</strong><span class="stove-sys-card-badge">1 recipe</span></div>
    <p class="stove-sys-card-desc">Spring Boot basic. Scala AUT, Kotlin tests.</p>
    <div class="stove-sys-card-actions">
      <a href="https://github.com/Trendyol/stove/tree/main/recipes/jvm/scala-recipes" target="_blank">recipes/jvm/scala-recipes ↗</a>
    </div>
  </div>

  <div class="stove-sys-card">
    <div class="stove-sys-card-head"><strong>Polyglot · Go</strong><span class="stove-sys-card-badge">process + container</span></div>
    <p class="stove-sys-card-desc">Full Go showcase: HTTP + Postgres + Kafka (sarama / franz / segmentio) + OTel + dashboard + coverage. Single project, both modes via <code>go.aut.mode</code>.</p>
    <div class="stove-sys-card-actions">
      <a href="https://github.com/Trendyol/stove/tree/main/recipes/process/golang" target="_blank">recipes/process/golang ↗</a>
    </div>
  </div>

</div>

## Pattern of a recipe

Every recipe page follows the same shape:

1. **What you'll test**. The business flow.
2. **Systems used**. Links to each system's reference page.
3. **Gradle dependencies**. Paste-ready.
4. **`StoveConfig.kt`**. Paste-ready.
5. **The test**. Paste-ready.
6. **Variations**. Ktor / Micronaut / Quarkus / Go equivalents where relevant.
7. **Common pitfalls**. What tends to break and how to diagnose it.

## Want a recipe for your flow?

If you have a scenario that's not covered, open an issue with a brief description (systems + the assertion you want to make). Recipes are derived from real test suites, so contributions are welcome.
