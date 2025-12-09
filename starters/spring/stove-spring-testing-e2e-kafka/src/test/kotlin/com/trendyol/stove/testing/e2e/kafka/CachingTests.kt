package com.trendyol.stove.testing.e2e.kafka

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

class CachingTests :
  FunSpec({

    test("should create cache that stores and retrieves values") {
      val cache = Caching.of<String, Int>()

      cache.put("key1", 100)
      cache.put("key2", 200)

      cache.getIfPresent("key1") shouldBe 100
      cache.getIfPresent("key2") shouldBe 200
    }

    test("should return null for non-existent keys") {
      val cache = Caching.of<String, String>()

      cache.getIfPresent("non-existent").shouldBeNull()
    }

    test("should overwrite existing values") {
      val cache = Caching.of<String, String>()

      cache.put("key", "original")
      cache.put("key", "updated")

      cache.getIfPresent("key") shouldBe "updated"
    }

    test("should support complex key types") {
      data class ComplexKey(
        val id: Int,
        val name: String
      )

      val cache = Caching.of<ComplexKey, String>()
      val key = ComplexKey(1, "test")

      cache.put(key, "value")

      cache.getIfPresent(key) shouldBe "value"
    }

    test("should support any value types") {
      data class ComplexValue(
        val data: List<String>,
        val count: Int
      )

      val cache = Caching.of<String, ComplexValue>()
      val value = ComplexValue(listOf("a", "b", "c"), 3)

      cache.put("key", value)

      cache.getIfPresent("key") shouldBe value
    }

    test("asMap should return all cached entries") {
      val cache = Caching.of<String, Int>()

      cache.put("one", 1)
      cache.put("two", 2)
      cache.put("three", 3)

      val map = cache.asMap()
      map.size shouldBe 3
      map["one"] shouldBe 1
      map["two"] shouldBe 2
      map["three"] shouldBe 3
    }

    test("should handle invalidation") {
      val cache = Caching.of<String, String>()

      cache.put("key", "value")
      cache.invalidate("key")

      cache.getIfPresent("key").shouldBeNull()
    }
  })
