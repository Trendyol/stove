package com.trendyol.stove.kafka.tests

import com.trendyol.stove.kafka.*
import com.trendyol.stove.kafka.intercepting.MessageStore
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import okio.ByteString.Companion.EMPTY
import okio.ByteString.Companion.toByteString

class MessageStoreTests :
  FunSpec({

    test("returns false when checking offset not yet committed") {
      val messageStore = MessageStore()

      val message1 = ConsumedMessage(
        id = "1",
        message = "message/1".toByteArray().toByteString(),
        topic = "topic",
        partition = 0,
        offset = 0,
        key = "key/1",
        headers = emptyMap(),
        unknownFields = EMPTY
      )

      val message2 = ConsumedMessage(
        id = "2",
        message = "message/2".toByteArray().toByteString(),
        topic = "topic",
        partition = 0,
        offset = 1,
        key = "key/2",
        headers = emptyMap(),
        unknownFields = EMPTY
      )
      messageStore.record(
        message1
      )
      messageStore.record(
        message2
      )

      val committedMessage1 = CommittedMessage(
        id = "1",
        topic = "topic",
        partition = 0,
        offset = message1.offset + 1,
        metadata = "",
        unknownFields = EMPTY
      )
      messageStore.record(committedMessage1)
      messageStore.isCommitted(
        "topic",
        0,
        0
      ) shouldBe true
      messageStore.isCommitted(
        "topic",
        1,
        0
      ) shouldBe false
    }
  })
