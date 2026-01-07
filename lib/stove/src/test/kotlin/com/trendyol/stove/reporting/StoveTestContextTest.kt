package com.trendyol.stove.reporting

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
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
  })
