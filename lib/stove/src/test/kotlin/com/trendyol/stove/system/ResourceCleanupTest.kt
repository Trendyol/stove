package com.trendyol.stove.system

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import kotlinx.coroutines.*

class ResourceCleanupTest : FunSpec({
  test("cleanup attempts every resource and preserves all failures in order") {
    val closed = mutableListOf<String>()
    val first = IllegalStateException("client close failed")
    val second = IllegalArgumentException("container stop failed")

    val failure = shouldThrow<IllegalStateException> {
      closeAll(
        {
          closed.add("client")
          throw first
        },
        {
          closed.add("container")
          throw second
        },
        { closed.add("application") }
      )
    }

    closed shouldBe listOf("client", "container", "application")
    failure shouldBeSameInstanceAs first
    failure.suppressed.toList() shouldBe listOf(second)
  }

  test("cancelling a test still allows suspending cleanup to finish") {
    val closed = mutableListOf<String>()
    val started = CompletableDeferred<Unit>()

    coroutineScope {
      val test = launch {
        try {
          started.complete(Unit)
          awaitCancellation()
        } finally {
          closeAll(listOf("client", "container")) {
            delay(1)
            closed.add(it)
          }
        }
      }
      started.await()
      test.cancelAndJoin()
    }

    closed shouldBe listOf("client", "container")
  }

  test("resources sharing a failure do not prevent remaining cleanup") {
    val failure = IllegalStateException("shared resource is unavailable")
    var lastResourceClosed = false

    shouldThrow<IllegalStateException> {
      closeAll(
        { throw failure },
        { throw failure },
        { lastResourceClosed = true }
      )
    } shouldBeSameInstanceAs failure

    lastResourceClosed shouldBe true
    failure.suppressed.toList() shouldBe emptyList()
  }
})
