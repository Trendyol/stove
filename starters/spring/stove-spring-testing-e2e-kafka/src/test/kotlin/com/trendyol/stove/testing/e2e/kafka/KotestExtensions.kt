package com.trendyol.stove.testing.e2e.kafka

import io.kotest.assertions.*
import io.kotest.assertions.print.Printed
import io.kotest.common.reflection.bestName
import io.kotest.matchers.errorCollector

inline fun <reified T : Throwable> shouldThrowMaybe(block: () -> Any) {
  val expectedExceptionClass = T::class
  val thrownThrowable = try {
    block()
    null
  } catch (thrown: Throwable) {
    thrown
  }

  when (thrownThrowable) {
    null -> Unit
    is T -> Unit
    is AssertionError -> errorCollector.collectOrThrow(thrownThrowable)
    else -> errorCollector.collectOrThrow(
      createAssertionError(
        "Expected exception ${expectedExceptionClass.bestName()} but a ${thrownThrowable::class.simpleName} was thrown instead.",
        cause = thrownThrowable,
        expected = Expected(Printed(expectedExceptionClass.bestName())),
        actual = Actual(Printed(thrownThrowable::class.simpleName ?: "null"))
      )
    )
  }
}
