package com.trendyol.stove.testing.e2e.serialization

import arrow.core.None
import com.fasterxml.jackson.core.JsonParseException
import com.fasterxml.jackson.databind.MapperFeature
import com.google.gson.JsonSyntaxException
import com.trendyol.stove.testing.e2e.serialization.StoveSerde.Companion.deserialize
import com.trendyol.stove.testing.e2e.serialization.StoveSerde.Companion.deserializeOption
import com.trendyol.stove.testing.e2e.serialization.StoveSerde.StoveSerdeProblem
import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.*
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.*

class SerializerTest : FunSpec({

  @Serializable
  data class TestData(
    val id: Int,
    val name: String,
    val tags: List<String> = listOf(),
    @SerialName("created_at")
    val createdAt: String? = null
  )

  val testData = TestData(
    id = 1,
    name = "Test Item",
    tags = listOf("tag1", "tag2"),
    createdAt = "2024-01-01"
  )

  context("StoveJacksonStringSerializer") {
    val serializer = StoveSerde.jackson.anyJsonStringSerde()

    test("should serialize and deserialize object correctly") {
      val serialized = serializer.serialize(testData)
      val deserialized = serializer.deserialize(serialized, TestData::class.java)

      deserialized shouldBe testData
      serialized shouldContain "\"id\":1"
      serialized shouldContain "\"name\":\"Test Item\""
    }

    test("should handle null values") {
      val dataWithNull = testData.copy(createdAt = null)
      val serialized = serializer.serialize(dataWithNull)
      val deserialized = serializer.deserialize(serialized, TestData::class.java)

      deserialized shouldBe dataWithNull
      serialized shouldNotContain "created_at"
    }

    test("should throw exception for invalid JSON") {
      shouldThrow<JsonParseException> {
        serializer.deserialize("invalid json", TestData::class.java)
      }
    }

    test("should return None when invalid JSON is deserialized") {
      val a = serializer.deserializeOption<TestData>("invalid json")
      a shouldBe None
    }

    test("should return Left when invalid JSON is deserialized") {
      val op = serializer.deserializeEither("invalid json", TestData::class.java)
      op.shouldBeLeft()
      op.value.message shouldContain "Unrecognized token 'invalid': was expecting (JSON String, Number, Array, Object or token 'null', 'true' or 'false')"
      op.value.shouldBeInstanceOf<StoveSerdeProblem.BecauseOfDeserialization>()
    }
  }

  context("StoveGsonStringSerializer") {
    val serializer = StoveSerde.gson.anyJsonStringSerde()

    test("should serialize and deserialize object correctly") {
      val serialized = serializer.serialize(testData)
      val deserialized = serializer.deserialize(serialized, TestData::class.java)

      deserialized shouldBe testData
      serialized shouldContain "\"id\":1"
      serialized shouldContain "\"name\":\"Test Item\""
    }

    test("should handle null values") {
      val dataWithNull = testData.copy(createdAt = null)
      val serialized = serializer.serialize(dataWithNull)
      val deserialized = serializer.deserialize(serialized, TestData::class.java)

      deserialized shouldBe dataWithNull
      serialized shouldNotContain "created_at"
    }

    test("should throw exception for invalid JSON") {
      shouldThrow<JsonSyntaxException> {
        serializer.deserialize("invalid json", TestData::class.java)
      }
    }
  }

  context("StoveKotlinxStringSerializer") {
    val serializer = StoveSerde.kotlinx.anyJsonStringSerde()

    test("should serialize and deserialize object correctly") {
      val serialized = serializer.serialize(testData)
      val deserialized = serializer.deserialize(serialized, TestData::class.java)
      val deserializedTyped = serializer.deserialize<TestData>(serialized)

      deserialized shouldBe testData
      deserializedTyped shouldBe testData
      serialized shouldContain "\"id\":1"
      serialized shouldContain "\"name\":\"Test Item\""
    }

    test("should handle null values") {
      val dataWithNull = testData.copy(createdAt = null)
      val serialized = serializer.serialize(dataWithNull)
      val deserialized = serializer.deserialize(serialized, TestData::class.java)

      deserialized shouldBe dataWithNull
      serialized shouldNotContain "created_at"
    }

    test("should throw exception for invalid JSON") {
      shouldThrow<SerializationException> {
        serializer.deserialize("invalid json", TestData::class.java)
      }
    }
  }

  context("Edge cases for all serializers") {
    val jacksonSerializer = StoveSerde.jackson.anyJsonStringSerde()
    val gsonSerializer = StoveSerde.gson.anyJsonStringSerde()
    val kotlinxSerializer = StoveSerde.kotlinx.anyJsonStringSerde()

    test("should handle empty lists") {
      val dataWithEmptyList = testData.copy(tags = emptyList())

      listOf(jacksonSerializer, gsonSerializer, kotlinxSerializer).forEach { serializer ->
        val serialized = serializer.serialize(dataWithEmptyList)
        val deserialized = serializer.deserialize(serialized, TestData::class.java)
        deserialized shouldBe dataWithEmptyList
        serialized shouldContain "\"tags\":[]"
      }
    }

    test("should handle special characters") {
      val dataWithSpecialChars = testData.copy(name = "Test \"Item\" with \\special/ chars")

      listOf(jacksonSerializer, gsonSerializer, kotlinxSerializer).forEach { serializer ->
        val serialized = serializer.serialize(dataWithSpecialChars)
        val deserialized = serializer.deserialize(serialized, TestData::class.java)
        deserialized shouldBe dataWithSpecialChars
      }
    }
  }

  context("configuring tests") {
    test("should configure StoveGson") {
      val gson = StoveGson.byConfiguring {
        setPrettyPrinting()
      }

      val serializer = StoveGsonStringSerializer<TestData>(gson)
      val serialized = serializer.serialize(testData)

      serialized shouldContain "\n"
    }

    test("should configure StoveKotlinx") {
      val json = StoveKotlinx.byConfiguring {
        ignoreUnknownKeys = false
        prettyPrint = true
      }

      val serializer = StoveKotlinxStringSerializer<TestData>(json)
      val serialized = serializer.serialize(testData)

      serialized shouldContain "\n"
    }

    test("should configure StoveJackson") {
      val objectMapper = StoveSerde.jackson.byConfiguring {
        enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS)
        enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        enable(com.fasterxml.jackson.databind.SerializationFeature.INDENT_OUTPUT)
      }
      val serializer = StoveJacksonStringSerializer<TestData>(objectMapper)
      val serialized = serializer.serialize(testData)

      serialized shouldContain "\n"
    }
  }
})
