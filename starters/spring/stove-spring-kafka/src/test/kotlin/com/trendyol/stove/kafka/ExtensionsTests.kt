package com.trendyol.stove.kafka

import arrow.core.*
import com.trendyol.stove.serialization.StoveSerde
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.producer.ProducerRecord

class ExtensionsTests :
  FunSpec({
    val serde = StoveSerde.jackson.anyByteArraySerde()

    test("toStoveMessage observes a tombstone producer record as an empty payload") {
      val record = ProducerRecord<String, Any?>("topic", null, "key-1", null)

      val message = record.toStoveMessage(serde)

      message.value.size shouldBe 0
      message.key shouldBe "key-1"
      message.topic shouldBe "topic"
    }

    test("toStoveMessage observes a tombstone consumer record as an empty payload") {
      val record = ConsumerRecord<String, Any?>("topic", 0, 42L, "key-1", null)

      val message = record.toStoveMessage(serde)

      message.value.size shouldBe 0
      message.offset shouldBe 42L
    }

    test("toStoveMessage passes raw ByteArray values through unchanged") {
      val poisonPill = "{not-valid-json!!".toByteArray()
      val record = ProducerRecord<String, Any?>("topic", null, "key-1", poisonPill)

      val message = record.toStoveMessage(serde)

      message.value.contentEquals(poisonPill) shouldBe true
    }

    test("toMetadata tolerates records without a key") {
      val record = ProducerRecord<String, Any?>("topic", null, null, "value")

      record.toMetadata().key shouldBe ""
    }

    test("addTestCase should add testCase to map when not present") {
      val map = mutableMapOf<String, String>("existingKey" to "existingValue")

      map.addTestCase("myTestCase".some())

      map["testCase"] shouldBe "myTestCase"
      map["existingKey"] shouldBe "existingValue"
    }

    test("addTestCase should not overwrite existing testCase") {
      val map = mutableMapOf<String, String>("testCase" to "existingTestCase")

      map.addTestCase("newTestCase".some())

      map["testCase"] shouldBe "existingTestCase"
    }

    test("addTestCase should do nothing when Option is None") {
      val map = mutableMapOf<String, String>("key" to "value")

      map.addTestCase(none())

      map.containsKey("testCase") shouldBe false
      map["key"] shouldBe "value"
    }

    test("addTestCase should return the same map") {
      val map = mutableMapOf<String, String>()

      val result = map.addTestCase("test".some())

      result shouldBe map
    }

    test("addTestCase with empty map and Some value") {
      val map = mutableMapOf<String, String>()

      map.addTestCase("firstTest".some())

      map.size shouldBe 1
      map["testCase"] shouldBe "firstTest"
    }

    test("addTestCase should preserve all existing entries") {
      val map = mutableMapOf(
        "header1" to "value1",
        "header2" to "value2",
        "header3" to "value3"
      )

      map.addTestCase("myTest".some())

      map.size shouldBe 4
      map["header1"] shouldBe "value1"
      map["header2"] shouldBe "value2"
      map["header3"] shouldBe "value3"
      map["testCase"] shouldBe "myTest"
    }
  })
