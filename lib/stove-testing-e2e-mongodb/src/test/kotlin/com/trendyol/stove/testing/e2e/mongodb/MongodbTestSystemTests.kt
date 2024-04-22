package com.trendyol.stove.testing.e2e.mongodb

import com.fasterxml.jackson.annotation.JsonAlias
import com.trendyol.stove.testing.e2e.system.TestSystem
import com.trendyol.stove.testing.e2e.system.abstractions.ApplicationUnderTest
import io.kotest.core.config.AbstractProjectConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.inspectors.forAny
import io.kotest.matchers.shouldBe
import org.bson.codecs.pojo.annotations.BsonCreator
import org.bson.codecs.pojo.annotations.BsonId
import org.bson.codecs.pojo.annotations.BsonProperty
import org.bson.types.ObjectId
import org.junit.jupiter.api.assertThrows

class Setup : AbstractProjectConfig() {
  override suspend fun beforeProject(): Unit =
    TestSystem()
      .with {
        mongodbDsl {
          tag("latest")
        }
        applicationUnderTest(NoOpApplication())
      }.run()

  override suspend fun afterProject(): Unit = TestSystem.stop()
}

class NoOpApplication : ApplicationUnderTest<Unit> {
  override suspend fun start(configurations: List<String>) {
  }

  override suspend fun stop() {
  }
}

class MongodbTestSystemTests : FunSpec({

  data class ExampleInstanceWithObjectId
    @BsonCreator
    constructor(
      @BsonId
      @JsonAlias("_id")
      val id: ObjectId,
      @BsonProperty("aggregateId") val aggregateId: String,
      @BsonProperty("description") val description: String
    )

  data class ExampleInstanceWithStringObjectId
    @BsonCreator
    constructor(
      @BsonId
      @JsonAlias("_id")
      val id: String,
      @BsonProperty("aggregateId") val aggregateId: String,
      @BsonProperty("description") val description: String
    )

  test("should save and get with objectId") {

    val id = ObjectId()
    TestSystem.validate {
      mongodb {
        saveToDefaultCollection(
          id.toHexString(),
          ExampleInstanceWithObjectId(
            id = id,
            aggregateId = id.toHexString(),
            description = testCase.name.testName
          )
        )
        shouldGet<ExampleInstanceWithObjectId>(id.toHexString()) { actual ->
          actual.aggregateId shouldBe id.toHexString()
          actual.description shouldBe testCase.name.testName
        }
      }
    }
  }

  test("should save and get with string objectId") {
    val id = ObjectId()
    TestSystem.validate {
      mongodb {
        saveToDefaultCollection(
          id.toHexString(),
          ExampleInstanceWithStringObjectId(
            id = id.toHexString(),
            aggregateId = id.toHexString(),
            description = testCase.name.testName
          )
        )
        shouldGet<ExampleInstanceWithStringObjectId>(id.toHexString()) { actual ->
          actual.aggregateId shouldBe id.toHexString()
          actual.description shouldBe testCase.name.testName
        }
      }
    }
  }

  test("Get with query should work") {
    val id1 = ObjectId()
    val id2 = ObjectId()
    val id3 = ObjectId()
    val firstDesc = "same description"
    val secondDesc = "different description"
    TestSystem.validate {
      mongodb {
        saveToDefaultCollection(
          id1.toHexString(),
          ExampleInstanceWithObjectId(
            id = id1,
            aggregateId = id1.toHexString(),
            description = firstDesc
          )
        )
        saveToDefaultCollection(
          id2.toHexString(),
          ExampleInstanceWithObjectId(
            id = id2,
            aggregateId = id2.toHexString(),
            description = secondDesc
          )
        )
        saveToDefaultCollection(
          id3.toHexString(),
          ExampleInstanceWithObjectId(
            id = id3,
            aggregateId = id3.toHexString(),
            description = secondDesc
          )
        )
        shouldQuery<ExampleInstanceWithObjectId>("{\"description\": \"$secondDesc\"}") { actual ->
          actual.count() shouldBe 2
          actual.forAny { it.id shouldBe id2 }
          actual.forAny { it.id shouldBe id3 }
        }
        shouldQuery<ExampleInstanceWithObjectId>("{\"description\": \"$firstDesc\"}") { actual ->
          actual.count() shouldBe 1
          actual.first().id shouldBe id1
        }
      }
    }
  }

  test("should throw assertion error when document does exist") {

    val id1 = ObjectId()
    TestSystem.validate {
      mongodb {
        saveToDefaultCollection(
          id1.toHexString(),
          ExampleInstanceWithObjectId(
            id = id1,
            aggregateId = id1.toHexString(),
            description = testCase.name.testName + "1"
          )
        )
        shouldGet<ExampleInstanceWithStringObjectId>(id1.toHexString()) { actual ->
          actual.aggregateId shouldBe id1.toHexString()
        }
        assertThrows<AssertionError> { shouldNotExist(id1.toHexString()) }
      }
    }
  }

  test("should not throw exception when given does not exist id") {
    val notExistDocId = ObjectId()
    TestSystem.validate {
      mongodb {
        shouldNotExist(notExistDocId.toHexString())
      }
    }
  }

  test("should delete") {
    val id = ObjectId()
    TestSystem.validate {
      mongodb {
        saveToDefaultCollection(
          id.toHexString(),
          ExampleInstanceWithObjectId(
            id = id,
            aggregateId = id.toHexString(),
            description = testCase.name.testName
          )
        )
        shouldQuery<ExampleInstanceWithObjectId>("{\"aggregateId\": \"${id.toHexString()}\"}") { actual ->
          actual.size shouldBe 1
        }
        shouldDelete(id.toHexString())
        shouldQuery<ExampleInstanceWithObjectId>("{\"aggregateId\": \"${id.toHexString()}\"}") { actual ->
          actual.size shouldBe 0
        }
      }
    }
  }
})
