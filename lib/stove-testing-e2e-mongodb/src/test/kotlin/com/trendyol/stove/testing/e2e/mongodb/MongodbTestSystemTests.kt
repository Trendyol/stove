package com.trendyol.stove.testing.e2e.mongodb

import com.fasterxml.jackson.annotation.JsonAlias
import com.trendyol.stove.testing.e2e.database.DatabaseSystem.Companion.shouldQuery
import com.trendyol.stove.testing.e2e.database.DocumentDatabaseSystem.Companion.shouldGet
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

class Setup : AbstractProjectConfig() {
    override suspend fun beforeProject() {
        TestSystem()
            .with {
                mongodbDsl {
                    tag("latest")
                }
            }
            .applicationUnderTest(NoOpApplication())
            .run()
    }

    override suspend fun afterProject() {
        TestSystem.instance.close()
    }
}

class NoOpApplication : ApplicationUnderTest<Unit> {
    override suspend fun start(configurations: List<String>) {
    }

    override suspend fun stop() {
    }
}

class MongodbTestSystemTests : FunSpec({

    test("should save and get with objectId") {
        data class ExampleInstanceWithObjectId @BsonCreator constructor(
            @BsonId
            @JsonAlias("_id")
            val id: ObjectId,
            @BsonProperty("aggregateId") val aggregateId: String,
            @BsonProperty("description") val description: String,
        )

        val id = ObjectId()
        TestSystem.instance
            .mongodb()
            .saveToDefaultCollection(
                id.toHexString(),
                ExampleInstanceWithObjectId(
                    id = id,
                    aggregateId = id.toHexString(),
                    description = testCase.name.testName
                )
            )
            .shouldGet<ExampleInstanceWithObjectId>(id.toHexString()) { actual ->
                actual.aggregateId shouldBe id.toHexString()
                actual.description shouldBe testCase.name.testName
            }
    }

    test("should save and get with string objectId") {
        data class ExampleInstanceWithStringObjectId @BsonCreator constructor(
            @BsonId
            @JsonAlias("_id")
            val id: String,
            @BsonProperty("aggregateId") val aggregateId: String,
            @BsonProperty("description") val description: String,
        )

        val id = ObjectId()
        TestSystem.instance
            .mongodb()
            .saveToDefaultCollection(
                id.toHexString(),
                ExampleInstanceWithStringObjectId(
                    id = id.toHexString(),
                    aggregateId = id.toHexString(),
                    description = testCase.name.testName
                )
            )
            .shouldGet<ExampleInstanceWithStringObjectId>(id.toHexString()) { actual ->
                actual.aggregateId shouldBe id.toHexString()
                actual.description shouldBe testCase.name.testName
            }
    }

    test("Get with query should work") {

        data class ExampleInstance @BsonCreator constructor(
            @BsonId
            @JsonAlias("_id")
            val id: ObjectId,
            @BsonProperty("aggregateId") val aggregateId: String,
            @BsonProperty("description") val description: String,
            @BsonProperty val isActive: Boolean = true,
        )

        val id1 = ObjectId()
        val id2 = ObjectId()
        val id3 = ObjectId()
        TestSystem.instance
            .mongodb()
            .saveToDefaultCollection(
                id1.toHexString(),
                ExampleInstance(
                    id = id1,
                    aggregateId = id1.toHexString(),
                    description = testCase.name.testName + "1"
                )
            )
            .saveToDefaultCollection(
                id2.toHexString(),
                ExampleInstance(
                    id = id2,
                    aggregateId = id2.toHexString(),
                    description = testCase.name.testName + "2"
                )
            )
            .saveToDefaultCollection(
                id3.toHexString(),
                ExampleInstance(
                    id = id3,
                    aggregateId = id3.toHexString(),
                    description = testCase.name.testName + "3",
                    isActive = false
                )
            )
            .shouldQuery<ExampleInstance>("{\"isActive\": true}") { actual ->
                actual.count() shouldBe 2
                actual.forAny { it.id shouldBe id1 }
                actual.forAny { it.id shouldBe id2 }
            }
            .shouldQuery<ExampleInstance>("{\"isActive\": false}") { actual ->
                actual.count() shouldBe 1
                actual.first().id shouldBe id3
            }
    }

    test("should delete") {
        data class ExampleInstance @BsonCreator constructor(
            @BsonId
            @JsonAlias("_id")
            val id: String,
            @BsonProperty("aggregateId") val aggregateId: String,
            @BsonProperty("description") val description: String,
        )

        val id = ObjectId()
        TestSystem.instance
            .mongodb()
            .saveToDefaultCollection(
                id.toHexString(),
                ExampleInstance(
                    id = id.toHexString(),
                    aggregateId = id.toHexString(),
                    description = testCase.name.testName
                )
            )
            .shouldQuery<ExampleInstance>("{\"aggregateId\": \"${id.toHexString()}\"}") { actual ->
                actual.size shouldBe 1
            }.then()
            .mongodb()
            .shouldDelete(id.toHexString())
            .shouldQuery<ExampleInstance>("{\"aggregateId\": \"${id.toHexString()}\"}") { actual ->
                actual.size shouldBe 0
            }
    }
})
