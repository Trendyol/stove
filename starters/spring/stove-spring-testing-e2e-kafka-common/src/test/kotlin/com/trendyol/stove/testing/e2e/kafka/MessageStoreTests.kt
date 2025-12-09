package com.trendyol.stove.testing.e2e.kafka

import com.trendyol.stove.testing.e2e.messaging.*
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.*
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class MessageStoreTests :
  FunSpec({

    test("should record and retrieve consumed messages") {
      val store = MessageStore()
      val metadata = MessageMetadata("test-topic", "key", emptyMap())
      val message = StoveMessage.consumed(
        topic = "test-topic",
        value = "test-value".toByteArray(),
        metadata = metadata,
        offset = 1L
      )

      store.record(message)

      val records = store.consumedRecords()
      records shouldHaveSize 1
      records.first() shouldBe message
    }

    test("should record and retrieve multiple consumed messages") {
      val store = MessageStore()
      val metadata = MessageMetadata("topic", "key", emptyMap())

      repeat(5) { i ->
        store.record(
          StoveMessage.consumed(
            topic = "topic-$i",
            value = "value-$i".toByteArray(),
            metadata = metadata,
            offset = i.toLong()
          )
        )
      }

      store.consumedRecords() shouldHaveSize 5
    }

    test("should record and retrieve published messages") {
      val store = MessageStore()
      val metadata = MessageMetadata("pub-topic", "pub-key", emptyMap())
      val message = StoveMessage.published(
        topic = "pub-topic",
        value = "published-value".toByteArray(),
        metadata = metadata
      )

      store.record(message)

      val records = store.producedRecords()
      records shouldHaveSize 1
      records.first() shouldBe message
    }

    test("should record and retrieve failed messages") {
      val store = MessageStore()
      val metadata = MessageMetadata("fail-topic", "fail-key", emptyMap())
      val failedMessage = StoveMessage.failed(
        topic = "fail-topic",
        value = "failed-value".toByteArray(),
        metadata = metadata,
        reason = RuntimeException("Test error")
      )
      val failure = Failure(
        message = ObservedMessage(failedMessage, metadata),
        reason = failedMessage.reason
      )

      store.record(failure)

      val records = store.failedRecords()
      records shouldHaveSize 1
      records.first().topic shouldBe "fail-topic"
      records.first().reason.message shouldBe "Test error"
    }

    test("should maintain separate stores for consumed, produced, and failed") {
      val store = MessageStore()
      val metadata = MessageMetadata("topic", "key", emptyMap())

      store.record(
        StoveMessage.consumed(
          topic = "consumed-topic",
          value = "consumed".toByteArray(),
          metadata = metadata
        )
      )
      store.record(
        StoveMessage.published(
          topic = "published-topic",
          value = "published".toByteArray(),
          metadata = metadata
        )
      )

      val failedMessage = StoveMessage.failed(
        topic = "failed-topic",
        value = "failed".toByteArray(),
        metadata = metadata,
        reason = RuntimeException("Error")
      )
      store.record(
        Failure(
          message = ObservedMessage(failedMessage, metadata),
          reason = failedMessage.reason
        )
      )

      store.consumedRecords() shouldHaveSize 1
      store.producedRecords() shouldHaveSize 1
      store.failedRecords() shouldHaveSize 1

      store.consumedRecords().first().topic shouldBe "consumed-topic"
      store.producedRecords().first().topic shouldBe "published-topic"
      store.failedRecords().first().topic shouldBe "failed-topic"
    }

    test("toString should include all message types") {
      val store = MessageStore()
      val metadata = MessageMetadata("topic", "key", emptyMap())

      store.record(
        StoveMessage.consumed(
          topic = "consumed-topic",
          value = "consumed".toByteArray(),
          metadata = metadata
        )
      )
      store.record(
        StoveMessage.published(
          topic = "published-topic",
          value = "published".toByteArray(),
          metadata = metadata
        )
      )

      val output = store.toString()
      output shouldContain "Consumed"
      output shouldContain "Published"
      output shouldContain "Failed"
    }

    test("should return empty lists when no messages recorded") {
      val store = MessageStore()

      store.consumedRecords() shouldBe emptyList()
      store.producedRecords() shouldBe emptyList()
      store.failedRecords() shouldBe emptyList()
    }
  })
