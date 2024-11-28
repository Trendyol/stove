package com.trendyol.stove.testing.e2e.mongodb

import com.fasterxml.jackson.annotation.JsonAlias
import com.trendyol.stove.testing.e2e.system.TestSystem
import com.trendyol.stove.testing.e2e.system.TestSystem.Companion.validate
import com.trendyol.stove.testing.e2e.system.abstractions.ApplicationUnderTest
import io.kotest.core.config.AbstractProjectConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.inspectors.forAny
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.delay
import org.bson.codecs.pojo.annotations.*
import org.bson.types.ObjectId
import org.junit.jupiter.api.assertThrows

class Setup : AbstractProjectConfig() {
  override suspend fun beforeProject() {
    TestSystem()
      .with {
        mongodb {
          MongodbSystemOptions {
            listOf()
          }
        }
        applicationUnderTest(NoOpApplication())
      }.run()

    validate {
      mongodb {
        println("pausing...")
        pause()

        delay(1000)

        println("unpausing...")
        unpause()

        delay(1000)

        println("operating normally...")
        println(inspect())
      }
    }
  }

  override suspend fun afterProject(): Unit = TestSystem.stop()
}

class NoOpApplication : ApplicationUnderTest<Unit> {
  override suspend fun start(configurations: List<String>) = Unit

  override suspend fun stop() = Unit
}

class MongodbTestSystemTests : FunSpec({
  data class ExampleInstanceWithObjectId(
    @BsonId
    @JsonAlias("_id")
    val id: ObjectId,
    @BsonProperty("aggregateId") val aggregateId: String,
    @BsonProperty("description") val description: String
  )

  data class ExampleInstanceWithStringObjectId(
    @JsonAlias("_id")
    val id: String,
    @BsonProperty("aggregateId") val aggregateId: String,
    @BsonProperty("description") val description: String
  )

  test("should save and get with objectId") {
    val id = ObjectId()
    validate {
      mongodb {
        save(
          ExampleInstanceWithObjectId(
            id = id,
            aggregateId = id.toHexString(),
            description = testCase.name.testName
          ),
          id.toHexString()
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
    validate {
      mongodb {
        save(
          ExampleInstanceWithStringObjectId(
            id = id.toHexString(),
            aggregateId = id.toHexString(),
            description = testCase.name.testName
          ),
          id.toHexString()
        )
        shouldGet<ExampleInstanceWithStringObjectId>(id.toHexString()) { actual ->
          actual.aggregateId shouldBe id.toHexString()
          actual.description shouldBe testCase.name.testName
        }
      }
    }
  }

  data class ExampleInstanceWithObjectIdForQuery(
    val id: String,
    val description: String
  )
  test("Get with query should work") {
    val id1 = ObjectId()
    val id2 = ObjectId()
    val id3 = ObjectId()
    val firstDesc = "same description"
    val secondDesc = "different description"
    validate {
      mongodb {
        save(
          ExampleInstanceWithObjectId(
            id = id1,
            aggregateId = id1.toHexString(),
            description = firstDesc
          ),
          id1.toHexString()
        )
        save(
          ExampleInstanceWithObjectId(
            id = id2,
            aggregateId = id2.toHexString(),
            description = secondDesc
          ),
          id2.toHexString()
        )
        save(
          ExampleInstanceWithObjectId(
            id = id3,
            aggregateId = id3.toHexString(),
            description = secondDesc
          ),
          id3.toHexString()
        )
        shouldQuery<ExampleInstanceWithObjectIdForQuery>("{\"description\": \"$secondDesc\"}") { actual ->
          actual.count() shouldBe 2
          actual.forAny { it.id shouldBe id2.toHexString() }
          actual.forAny { it.id shouldBe id3.toHexString() }
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
    validate {
      mongodb {
        save(
          ExampleInstanceWithObjectId(
            id = id1,
            aggregateId = id1.toHexString(),
            description = testCase.name.testName + "1"
          ),
          id1.toHexString()
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
    validate {
      mongodb {
        shouldNotExist(notExistDocId.toHexString())
      }
    }
  }

  test("should delete") {
    val id = ObjectId()
    validate {
      mongodb {
        save(
          ExampleInstanceWithObjectId(
            id = id,
            aggregateId = id.toHexString(),
            description = testCase.name.testName
          ),
          id.toHexString()
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

  test("complex type") {
    data class Nested(
      val id: String,
      val name: String
    )

    data class ComplexType(
      val id: String,
      val name: String,
      val nested: Nested
    )

    val id = ObjectId()
    val nestedId = ObjectId()
    validate {
      mongodb {
        save(
          ComplexType(
            id = id.toHexString(),
            name = "name",
            nested = Nested(
              id = nestedId.toHexString(),
              name = "nested"
            )
          ),
          id.toHexString()
        )
        shouldGet<ComplexType>(id.toHexString()) { actual ->
          actual.id shouldBe id.toHexString()
          actual.name shouldBe "name"
          actual.nested.id shouldBe actual.nested.id
          actual.nested.name shouldBe "nested"
        }

        shouldQuery<ComplexType>(
          query = "{\"nested.id\": \"${nestedId.toHexString()}\"}"
        ) { actual ->
          actual.size shouldBe 1
          actual.first().id shouldBe id.toHexString()
        }
      }
    }
  }
})
