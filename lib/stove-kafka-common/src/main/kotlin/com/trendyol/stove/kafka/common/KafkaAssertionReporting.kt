package com.trendyol.stove.kafka.common

import arrow.core.*
import com.trendyol.stove.reporting.*
import kotlinx.coroutines.CancellationException
import kotlin.time.Duration

/** Records a Kafka assertion result while preserving the caller's cancellation semantics. */
suspend fun <T : Any> runKafkaAssertion(
  reporter: StoveReporter,
  systemName: String,
  assertionName: String,
  typeName: String,
  timeout: Duration,
  expected: String,
  block: suspend ((T) -> Unit) -> Unit
) {
  var matchedMessage: T? = null
  val failure = try {
    block { matchedMessage = it }
    null
  } catch (exception: CancellationException) {
    throw exception
  } catch (exception: Throwable) {
    exception
  }

  if (failure == null) {
    reporter.record(
      ReportEntry.success(
        system = systemName,
        testId = reporter.currentTestId(),
        action = "$assertionName<$typeName>",
        output = matchedMessage.toOption(),
        metadata = mapOf("timeout" to timeout.toString())
      )
    )
  } else {
    reporter.record(
      ReportEntry.failure(
        system = systemName,
        testId = reporter.currentTestId(),
        action = "$assertionName<$typeName>",
        error = failure.message ?: failure::class.simpleName ?: "Kafka assertion failed",
        expected = expected.some(),
        actual = (matchedMessage ?: "No matching message found").some()
      )
    )
  }

  failure?.let { throw it }
}
