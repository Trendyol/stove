package com.trendyol.stove.testing.e2e.kafka

import com.trendyol.stove.testing.e2e.messaging.MessageMetadata
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class StoveMessageTests :
  FunSpec({

    test("consumed message should be created with factory method") {
      val metadata = MessageMetadata("test-topic", "test-key", mapOf("header1" to "value1"))
      val message = StoveMessage.consumed(
        topic = "test-topic",
        value = "test-value".toByteArray(),
        metadata = metadata,
        partition = 0,
        key = "test-key",
        timestamp = 1234567890L,
        offset = 100L
      )

      message.topic shouldBe "test-topic"
      message.valueAsString shouldBe "test-value"
      message.metadata shouldBe metadata
      message.partition shouldBe 0
      message.key shouldBe "test-key"
      message.timestamp shouldBe 1234567890L
      message.offset shouldBe 100L
    }

    test("published message should be created with factory method") {
      val metadata = MessageMetadata("test-topic", "test-key", emptyMap())
      val message = StoveMessage.published(
        topic = "test-topic",
        value = "published-value".toByteArray(),
        metadata = metadata,
        partition = 1,
        key = "pub-key",
        timestamp = 9876543210L
      )

      message.topic shouldBe "test-topic"
      message.valueAsString shouldBe "published-value"
      message.metadata shouldBe metadata
      message.partition shouldBe 1
      message.key shouldBe "pub-key"
      message.timestamp shouldBe 9876543210L
    }

    test("failed message should be created with factory method") {
      val metadata = MessageMetadata("error-topic", "error-key", mapOf("error" to "true"))
      val exception = RuntimeException("Test failure")
      val message = StoveMessage.failed(
        topic = "error-topic",
        value = "failed-value".toByteArray(),
        metadata = metadata,
        reason = exception,
        partition = 2,
        key = "error-key",
        timestamp = 1111111111L
      )

      message.topic shouldBe "error-topic"
      message.valueAsString shouldBe "failed-value"
      message.metadata shouldBe metadata
      message.partition shouldBe 2
      message.key shouldBe "error-key"
      message.timestamp shouldBe 1111111111L
      message.reason shouldBe exception
    }

    test("consumed messages with same content should be equal") {
      val metadata = MessageMetadata("topic", "key", emptyMap())
      val value = "same-value".toByteArray()

      val message1 = StoveMessage.consumed(
        topic = "topic",
        value = value,
        metadata = metadata,
        partition = 0,
        key = "key",
        timestamp = 123L,
        offset = 1L
      )

      val message2 = StoveMessage.consumed(
        topic = "topic",
        value = value.copyOf(),
        metadata = metadata,
        partition = 0,
        key = "key",
        timestamp = 123L,
        offset = 1L
      )

      message1 shouldBe message2
      message1.hashCode() shouldBe message2.hashCode()
    }

    test("consumed messages with different offsets should not be equal") {
      val metadata = MessageMetadata("topic", "key", emptyMap())
      val value = "same-value".toByteArray()

      val message1 = StoveMessage.consumed(
        topic = "topic",
        value = value,
        metadata = metadata,
        offset = 1L
      )

      val message2 = StoveMessage.consumed(
        topic = "topic",
        value = value.copyOf(),
        metadata = metadata,
        offset = 2L
      )

      message1 shouldNotBe message2
    }

    test("failed messages with different reasons should not be equal") {
      val metadata = MessageMetadata("topic", "key", emptyMap())
      val value = "value".toByteArray()

      val message1 = StoveMessage.failed(
        topic = "topic",
        value = value,
        metadata = metadata,
        reason = RuntimeException("Error 1")
      )

      val message2 = StoveMessage.failed(
        topic = "topic",
        value = value.copyOf(),
        metadata = metadata,
        reason = RuntimeException("Error 2")
      )

      message1 shouldNotBe message2
    }

    test("message with null optional fields should be created successfully") {
      val metadata = MessageMetadata("topic", "null", emptyMap())
      val message = StoveMessage.consumed(
        topic = "topic",
        value = "value".toByteArray(),
        metadata = metadata
      )

      message.partition shouldBe null
      message.key shouldBe null
      message.timestamp shouldBe null
      message.offset shouldBe null
    }
  })
