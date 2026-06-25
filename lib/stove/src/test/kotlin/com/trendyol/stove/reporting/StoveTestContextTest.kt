package com.trendyol.stove.reporting

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext

class StoveTestContextTest :
  FunSpec({

    test("StoveTestContext is a CoroutineContext element") {
      val ctx = StoveTestContext("TestSpec::test1", "test1", "TestSpec")

      ctx.testId shouldBe "TestSpec::test1"
      ctx.testName shouldBe "test1"
      ctx.specName shouldBe "TestSpec"
      ctx.key shouldBe StoveTestContext.Key
    }

    test("currentStoveTestContext retrieves context from coroutine") {
      val ctx = StoveTestContext("test-1", "test1")
      val contextWithStove = currentCoroutineContext() + ctx

      withContext(contextWithStove) {
        currentStoveTestContext() shouldBe ctx
      }
    }

    test("StoveTestContextHolder stores context in ThreadLocal") {
      val ctx = StoveTestContext("test-1", "test1")

      StoveTestContextHolder.set(ctx)
      StoveTestContextHolder.get() shouldBe ctx

      StoveTestContextHolder.clear()
      StoveTestContextHolder.get() shouldBe null
    }

    test("mirrors itself into StoveTestContextHolder across dispatcher thread switches") {
      val ctx = StoveTestContext("test-1", "test1")

      withContext(ctx) {
        // Resuming on another worker thread must still see the context via the
        // ThreadLocal mirror, so non-suspend resolvers (resolveTestId) work.
        withContext(Dispatchers.IO) {
          StoveTestContextHolder.get() shouldBe ctx
        }
      }
    }

    test("restores previous holder state after the context scope ends") {
      val ctx = StoveTestContext("test-1", "test1")

      withContext(ctx) {
        StoveTestContextHolder.get() shouldBe ctx
      }

      // Outside the scope the mirror must be cleared, not leaked onto this thread.
      StoveTestContextHolder.get().shouldBeNull()
    }
  })
