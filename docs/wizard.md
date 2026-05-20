---
hide:
  - navigation
  - toc
---

# Setup Wizard

Answer a few questions and the wizard composes a complete Stove setup for your project:

- the **Gradle dependencies** to add
- a ready-to-paste **`StoveConfig.kt`**
- a **sample test** that exercises every system you picked

The wizard runs entirely in your browser. Nothing is sent anywhere. Your selections are reflected in the URL, so you can share or bookmark a configuration.

!!! note "Review before pasting"
    The generated setup is a starting point. Before committing it, review package names, imports, your application runner, fixed ports, property names, and version alignment between the Stove BOM, `stoveTracing` plugin, Stove dependencies, and `stove-cli`.

<div id="stove-wizard" markdown="0">
  <noscript>
    <div class="admonition warning">
      <p class="admonition-title">JavaScript required</p>
      <p>The setup wizard needs JavaScript to run. If you'd rather not enable it, follow <a href="getting-started/">Getting Started</a> instead. It walks through the same setup manually.</p>
    </div>
  </noscript>
</div>

!!! tip "Prefer the manual route?"
    The wizard is a shortcut. The full reference lives under [Getting Started](getting-started.md), [Supported Frameworks](frameworks/index.md), and [Components](Components/index.md).
