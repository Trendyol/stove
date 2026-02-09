package com.trendyol.stove.mongodb

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bson.types.ObjectId

class ObjectIdJsonOperationsTest :
  FunSpec({

    val mapper = ObjectMapper()
      .registerModule(KotlinModule.Builder().build())
      .registerModule(ObjectIdModule())

    test("ObjectIdSerializer should serialize ObjectId to hex string") {
      val objectId = ObjectId("507f1f77bcf86cd799439011")
      val container = ObjectIdContainer(objectId)

      val json = mapper.writeValueAsString(container)

      json shouldBe """{"id":"507f1f77bcf86cd799439011"}"""
    }

    test("ObjectIdDeserializer should deserialize hex string to ObjectId") {
      val json = """{"id":"507f1f77bcf86cd799439011"}"""

      val container = mapper.readValue<ObjectIdContainer>(json)

      container.id shouldBe ObjectId("507f1f77bcf86cd799439011")
    }

    test("ObjectIdDeserializer should deserialize MongoDB extended JSON format") {
      val json = "{\"id\":{\"\$oid\":\"507f1f77bcf86cd799439011\"}}"

      val container = mapper.readValue<ObjectIdContainer>(json)

      container.id shouldBe ObjectId("507f1f77bcf86cd799439011")
    }

    test("round-trip serialization should preserve ObjectId") {
      val original = ObjectIdContainer(ObjectId("507f1f77bcf86cd799439011"))

      val json = mapper.writeValueAsString(original)
      val deserialized = mapper.readValue<ObjectIdContainer>(json)

      deserialized.id shouldBe original.id
    }

    test("ObjectIdModule should register both serializer and deserializer") {
      val freshMapper = ObjectMapper()
        .registerModule(KotlinModule.Builder().build())
        .registerModule(ObjectIdModule())

      val objectId = ObjectId()
      val json = freshMapper.writeValueAsString(ObjectIdContainer(objectId))
      val result = freshMapper.readValue<ObjectIdContainer>(json)

      result.id shouldBe objectId
    }

    test("should handle multiple ObjectId fields") {
      val id1 = ObjectId("507f1f77bcf86cd799439011")
      val id2 = ObjectId("507f191e810c19729de860ea")
      val container = MultiObjectIdContainer(id1, id2)

      val json = mapper.writeValueAsString(container)
      val result = mapper.readValue<MultiObjectIdContainer>(json)

      result.first shouldBe id1
      result.second shouldBe id2
    }
  })

data class ObjectIdContainer(
  val id: ObjectId
)

data class MultiObjectIdContainer(
  val first: ObjectId,
  val second: ObjectId
)
