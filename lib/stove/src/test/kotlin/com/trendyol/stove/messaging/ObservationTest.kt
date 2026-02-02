package com.trendyol.stove.messaging

import arrow.core.None
import arrow.core.Some
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class ObservationTest :
  FunSpec({

    val testMetadata = MessageMetadata(
      topic = "test-topic",
      key = "test-key",
      headers = mapOf("header1" to "value1")
    )

    context("MessageMetadata") {
      test("should store topic, key, and headers") {
        val metadata = MessageMetadata(
          topic = "orders",
          key = "order-123",
          headers = mapOf("traceId" to "abc123", "version" to 1)
        )

        metadata.topic shouldBe "orders"
        metadata.key shouldBe "order-123"
        metadata.headers["traceId"] shouldBe "abc123"
        metadata.headers["version"] shouldBe 1
      }

      test("should support empty headers") {
        val metadata = MessageMetadata(
          topic = "events",
          key = "event-1",
          headers = emptyMap()
        )

        metadata.headers shouldBe emptyMap()
      }
    }

    context("SuccessfulParsedMessage") {
      test("should implement ParsedMessage") {
        val message = SuccessfulParsedMessage(
          message = Some("test-content"),
          metadata = testMetadata
        )

        message.shouldBeInstanceOf<ParsedMessage<String>>()
      }

      test("should store message and metadata") {
        val message = SuccessfulParsedMessage(
          message = Some(mapOf("id" to 123)),
          metadata = testMetadata
        )

        message.message shouldBe Some(mapOf("id" to 123))
        message.metadata shouldBe testMetadata
      }

      test("should handle None message") {
        val message = SuccessfulParsedMessage<String>(
          message = None,
          metadata = testMetadata
        )

        message.message shouldBe None
      }
    }

    context("FailedParsedMessage") {
      test("should implement ParsedMessage") {
        val exception = RuntimeException("Parse error")
        val message = FailedParsedMessage(
          message = None,
          metadata = testMetadata,
          reason = exception
        )

        message.shouldBeInstanceOf<ParsedMessage<String>>()
      }

      test("should store reason for failure") {
        val exception = IllegalArgumentException("Invalid JSON")
        val message = FailedParsedMessage(
          message = None,
          metadata = testMetadata,
          reason = exception
        )

        message.reason shouldBe exception
        message.reason.message shouldBe "Invalid JSON"
      }

      test("should preserve partial message on failure") {
        val exception = RuntimeException("Validation failed")
        val message = FailedParsedMessage(
          message = Some("partial-data"),
          metadata = testMetadata,
          reason = exception
        )

        message.message shouldBe Some("partial-data")
      }
    }

    context("ObservedMessage") {
      test("should store actual message and metadata") {
        data class OrderEvent(
          val orderId: String,
          val amount: Double
        )

        val event = OrderEvent("order-123", 99.99)
        val observed = ObservedMessage(
          actual = event,
          metadata = testMetadata
        )

        observed.actual shouldBe event
        observed.metadata shouldBe testMetadata
      }

      test("should work with primitive types") {
        val observed = ObservedMessage(
          actual = "simple-string",
          metadata = testMetadata
        )

        observed.actual shouldBe "simple-string"
      }
    }

    context("FailedObservedMessage") {
      test("should extend ObservedMessage") {
        val exception = RuntimeException("Processing failed")
        val failed = FailedObservedMessage(
          actual = "message-content",
          metadata = testMetadata,
          reason = exception
        )

        failed.shouldBeInstanceOf<ObservedMessage<String>>()
      }

      test("should store failure reason") {
        val exception = IllegalStateException("Connection lost")
        val failed = FailedObservedMessage(
          actual = 42,
          metadata = testMetadata,
          reason = exception
        )

        failed.actual shouldBe 42
        failed.metadata shouldBe testMetadata
        failed.reason shouldBe exception
      }
    }

    context("Failure") {
      test("should wrap observed message with failure reason") {
        val observed = ObservedMessage(
          actual = "test-data",
          metadata = testMetadata
        )
        val exception = RuntimeException("Assertion failed")

        val failure = Failure(
          message = observed,
          reason = exception
        )

        failure.message shouldBe observed
        failure.reason shouldBe exception
      }

      test("should work with FailedObservedMessage") {
        val innerException = IllegalArgumentException("Parse error")
        val outerException = RuntimeException("Retry exhausted")

        val failedObserved = FailedObservedMessage(
          actual = "data",
          metadata = testMetadata,
          reason = innerException
        )

        val failure = Failure(
          message = failedObserved,
          reason = outerException
        )

        (failure.message as FailedObservedMessage).reason shouldBe innerException
        failure.reason shouldBe outerException
      }
    }
  })
