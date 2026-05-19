---
hide:
  - toc
---

# Playground

Live runnable Kotlin via [KotlinPlayground](https://github.com/JetBrains/kotlin-playground). Edit and hit ▶ to compile + run in the browser.

<div class="stove-tldr" markdown>
<span class="stove-tldr-title">Heads up</span>
Playground runs <strong>vanilla Kotlin</strong> in a sandbox. The Stove DSL needs Docker, your AUT, and the JVM agent. Use this page to feel the <em>shape</em> of the DSL. Real tests run in your IDE or via <code>./gradlew e2eTest</code>.
</div>

## Try the DSL shape

<pre class="kotlin-runnable" data-target-platform="java" theme="darcula">
// Pretend `stove { }`, `http { }`, `kafka { }` come from the Stove DSL.
// Here we just illustrate the shape so you can see what your test code looks like.

class FakeResponse(val status: Int, val body: String)

fun stove(block: StoveDsl.() -> Unit) = StoveDsl().apply(block)

class StoveDsl {
  fun http(block: HttpDsl.() -> Unit) = HttpDsl().apply(block)
  fun kafka(block: KafkaDsl.() -> Unit) = KafkaDsl().apply(block)
}
class HttpDsl {
  fun get(uri: String, assert: (FakeResponse) -> Unit) {
    assert(FakeResponse(200, "{\"hello\":\"world\"}"))
  }
}
class KafkaDsl {
  fun shouldBePublished(predicate: () -> Boolean) {
    require(predicate()) { "event not seen" }
    println("✓ event published")
  }
}

fun main() {
  stove {
    http {
      get("/hello") { response ->
        check(response.status == 200) { "expected 200" }
        println("got body of length: ${response.body.length}")
      }
    }
    kafka {
      shouldBePublished { true }
    }
  }
  println("test passed")
}
</pre>

## Use in your own pages

Any `<pre>` or `<code>` tagged with `class="kotlin-runnable"` becomes interactive:

```html
<pre class="kotlin-runnable" data-target-platform="java" theme="darcula">
fun main() {
  println("Hello, Stove!")
}
</pre>
```

Optional data attributes:

| Attr | Default | Use |
|---|---|---|
| `data-target-platform` | `java` | `js` for JS target, `junit` for JUnit assertions |
| `theme` | `darcula` | `idea` for light theme |
| `data-highlight-only` | (off) | Set to `true` for read-only highlighting |

See the [KotlinPlayground reference](https://github.com/JetBrains/kotlin-playground) for the full attribute list.

## Next

- [Wizard](wizard.md) — compose your real setup
- [Recipes](recipes/index.md) — end-to-end paste-ready scenarios
- [Getting Started](getting-started.md) — manual setup walkthrough
