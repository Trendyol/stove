package com.trendyol.stove.testing.e2e.kafka

import io.kotest.assertions.*
import io.kotest.assertions.print.Printed
import io.kotest.common.reflection.bestName
import io.kotest.core.config.AbstractProjectConfig
import io.kotest.engine.concurrency.SpecExecutionMode
import io.kotest.matchers.errorCollector

/**
 * Shared Kafka test configuration - runs tests sequentially.
 */
abstract class KafkaTestSetup : AbstractProjectConfig() {
  override val specExecutionMode: SpecExecutionMode = SpecExecutionMode.Sequential
}

/**
 * Test exception thrown to verify error handling in Kafka consumers.
 */
class StoveBusinessException(
  message: String
) : Exception(message)

/**
 * Asserts that a block either completes successfully or throws the expected exception type.
 * Unlike shouldThrow, this doesn't fail if no exception is thrown.
 */
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
