package com.trendyol.stove.system

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.*

class ResourceCleanupTest : FunSpec({
  test("the thrown failure retains suppressed cleanup errors across the coroutine boundary") {
    val primary = IllegalStateException("first")
    val secondary = IllegalArgumentException("second")
    val actual = shouldThrow<IllegalStateException> {
      closeAll({ throw primary }, { throw secondary }, { throw primary })
    }
    actual shouldBe primary
    actual.suppressed.toList() shouldBe listOf(secondary)
  }

  test("cancellation allows all suspending cleanup actions to finish") {
    val events = mutableListOf<String>()
    coroutineScope {
      val entered = CompletableDeferred<Unit>()
      val worker = launch {
        try {
          entered.complete(Unit)
          awaitCancellation()
        } finally {
          closeAll(
            {
              delay(1)
              events += "first"
            },
            {
              yield()
              events += "second"
            }
          )
        }
      }
      entered.await()
      worker.cancelAndJoin()
    }
    events shouldBe listOf("first", "second")
  }
})
