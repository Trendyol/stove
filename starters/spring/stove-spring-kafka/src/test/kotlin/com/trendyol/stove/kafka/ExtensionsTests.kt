package com.trendyol.stove.kafka

import arrow.core.*
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ExtensionsTests :
  FunSpec({

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
