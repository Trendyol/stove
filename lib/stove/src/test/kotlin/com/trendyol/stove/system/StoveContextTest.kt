package com.trendyol.stove.system

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext

class StoveContextTest : FunSpec({
  test("validation helpers and child coroutines inherit the bound instance") {
    val bound = Stove()
    withContext(StoveContext(bound)) {
      stove {
        this.stove shouldBeSameInstanceAs bound
        coroutineScope {
          async(Dispatchers.Default) {
            stove {
              this.stove shouldBeSameInstanceAs bound
              currentCoroutineContext()[StoveContext]?.stove shouldBeSameInstanceAs bound
            }
          }.await()
        }
      }
    }
    currentCoroutineContext()[StoveContext] shouldBe null
  }

  test("a failing nested binding restores its enclosing context") {
    val outer = Stove()
    val inner = Stove()
    withContext(StoveContext(outer)) {
      shouldThrow<IllegalStateException> {
        withContext(Dispatchers.Default + StoveContext(inner)) {
          stove {
            this.stove shouldBeSameInstanceAs inner
            error("nested failure")
          }
        }
      }
      stove {
        this.stove shouldBeSameInstanceAs outer
        currentCoroutineContext()[StoveContext]?.stove shouldBeSameInstanceAs outer
      }
    }
    currentCoroutineContext()[StoveContext] shouldBe null
  }
})
