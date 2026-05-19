# Recipes

End-to-end scenarios that combine multiple systems. Each recipe is a complete, copy-paste setup. Gradle deps, `StoveConfig.kt`, and a runnable test. For a real-world flow.

Recipes complement the [Setup Wizard](../index.md) (which composes systems) and the [Components reference](../Components/index.md) (which documents each system in isolation).

## Available recipes

<div class="grid cards" markdown>

-   :material-cart-check: **Order placement flow**

    HTTP POST → Postgres write → WireMock external call → Kafka event published.

    [Order flow](order-flow.md)

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
