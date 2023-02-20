package com.trendyol.stove.testing.e2e.mongodb

import com.fasterxml.jackson.annotation.JsonAlias
import com.trendyol.stove.testing.e2e.database.DocumentDatabaseSystem.Companion.shouldGet
import com.trendyol.stove.testing.e2e.system.TestSystem
import com.trendyol.stove.testing.e2e.system.abstractions.ApplicationUnderTest
import io.kotest.core.config.AbstractProjectConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bson.codecs.pojo.annotations.BsonCreator
import org.bson.codecs.pojo.annotations.BsonId
import org.bson.codecs.pojo.annotations.BsonProperty
import org.bson.types.ObjectId

class Setup : AbstractProjectConfig() {
    override suspend fun beforeProject() {
        TestSystem()
            .withMongodb {
                mongodb {
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

    data class ExampleInstanceWithObjectId @BsonCreator constructor(
        @BsonId
        @JsonAlias("_id")
        val id: ObjectId,
        @BsonProperty("aggregateId") val aggregateId: String,
        @BsonProperty("description") val description: String,
    )

    data class ExampleInstanceWithStringObjectId @BsonCreator constructor(
        @BsonId
        @JsonAlias("_id")
        val id: String,
        @BsonProperty("aggregateId") val aggregateId: String,
        @BsonProperty("description") val description: String,
    )

    test("should save and get with objectId") {
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
})
