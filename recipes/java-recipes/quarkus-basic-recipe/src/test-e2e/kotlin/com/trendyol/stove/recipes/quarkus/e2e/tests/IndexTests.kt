package com.trendyol.stove.recipes.quarkus.e2e.tests

import com.trendyol.stove.recipes.quarkus.*
import com.trendyol.stove.testing.e2e.http.http
import com.trendyol.stove.testing.e2e.system.TestSystem.Companion.validate
import com.trendyol.stove.testing.e2e.system.using
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.*
import io.kotest.matchers.shouldBe

class IndexTests :
  FunSpec({

    test("Index page should return 200") {
      validate {
        http {
          get<String>(
            "/hello",
            headers = mapOf(
              "Content-Type" to "text/plain",
              "Accept" to "text/plain"
            )
          ) { actual ->
            actual shouldBe "Hello from Quarkus Service"
          }
        }
      }
    }

    test("bridge should resolve single bean") {
      validate {
        using<HelloService> {
          val result = hello()
          result shouldBe "Hello from Quarkus Service"
          println("[Test] Successfully resolved HelloService: $result")
        }
      }
    }

    test("bridge should resolve all implementations of an interface") {
      validate {
        // Resolve ALL implementations of GreetingService via List<T>
        using<List<GreetingService>> {
          this shouldHaveSize 3

          val languages = map { it.getLanguage() }
          languages shouldContainExactlyInAnyOrder listOf("English", "Spanish", "Turkish")

          forEach { service ->
            println("[Test] ${service.getLanguage()}: ${service.greet("World")}")
          }
        }
      }
    }

    test("bridge should allow interacting with repository using primitives") {
      validate {
        using<ItemRepository> {
          clear()
          count() shouldBe 0

          // ✅ Primitives work across classloader boundary
          add("item-1", "First Item")
          add("item-2", "Second Item")
          add("item-3", "Third Item")

          count() shouldBe 3
          getById("item-1") shouldBe "First Item"
          getById("item-2") shouldBe "Second Item"
          getById("item-3") shouldBe "Third Item"

          getAllIds() shouldContainExactlyInAnyOrder listOf("item-1", "item-2", "item-3")

          println("[Test] ✅ Successfully interacted with repository using primitives")

          clear()
        }
      }
    }

    // ============================================================
    // LIMITATION TESTS
    // ============================================================

    test("LIMITATION: passing complex objects across classloader fails") {
      validate {
        using<ItemRepository> {
          clear()

          // ❌ Item created in test classloader cannot be passed to Quarkus
          val testItem = Item("test-1", "Test Item")

          val exception = shouldThrow<java.lang.reflect.UndeclaredThrowableException> {
            addItem(testItem)
          }

          println("[Test] ❌ Expected: ${exception.cause?.javaClass?.simpleName}")
          println("[Test] Complex objects from test CL can't be passed to Quarkus")
        }
      }
    }

    test("LIMITATION: returned complex objects cause ClassCastException") {
      validate {
        using<ItemRepository> {
          clear()
          add("item-x", "Complex Item")

          // ❌ Returned Item from Quarkus CL can't be cast to test's Item
          val exception = shouldThrow<ClassCastException> {
            getItemById("item-x")
          }

          println("[Test] ❌ Expected: ClassCastException")
          println("[Test] Returned objects from Quarkus CL can't be cast to test types")

          clear()
        }
      }
    }
  })
