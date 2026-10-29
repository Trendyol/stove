package com.trendyol.stove.kafka

import io.kotest.assertions.*
import io.kotest.assertions.print.Printed
import io.kotest.common.reflection.bestName
import io.kotest.core.config.AbstractProjectConfig
import io.kotest.engine.concurrency.SpecExecutionMode

/**
 * Application specs replace the global Stove instance and serde, so start them sequentially.
 * Independent root tests run concurrently within each application.
 */
abstract class KafkaTestSetup : AbstractProjectConfig() {
  override val specExecutionMode: SpecExecutionMode = SpecExecutionMode.Sequential
  override val testExecutionMode = io.kotest.engine.concurrency.TestExecutionMode.LimitedConcurrency(
    com.trendyol.stove.testCaseConcurrency(parallelSpecs = 1)
  )
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

/** Separate application routes, partitions and consumer groups for each root scenario. */
object StringSerdeTestTopics {
  private val suffix = java.util.UUID.randomUUID().toString()
  val publish = "publish-$suffix"
  val serde = "serde-$suffix"
  val failing = "failing-$suffix"
  val group = "string-$suffix"
}

object ProtobufSerdeTestTopics {
  private val suffix = java.util.UUID.randomUUID().toString()
  val topic = "protobuf-$suffix"
  val group = "protobuf-$suffix"
  val registry = "mock://protobuf-$suffix"
}
