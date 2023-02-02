package com.trendyol.stove.testing.e2e.couchbase

import com.trendyol.stove.testing.e2e.database.KeyValueDatabaseSystem.Companion.shouldGet
import com.trendyol.stove.testing.e2e.system.TestSystem
import com.trendyol.stove.testing.e2e.system.abstractions.ApplicationUnderTest
import io.kotest.core.config.AbstractProjectConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.UUID

class Setup : AbstractProjectConfig() {
    override suspend fun beforeProject() {
        TestSystem()
            .withCouchbase(UUID.randomUUID().toString())
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

class CouchbaseTestSystemTests : FunSpec({

    data class ExampleInstance(
        val id: String,
        val description: String,
    )

    test("should save and get") {
        val id = UUID.randomUUID().toString()
        TestSystem.instance
            .couchbase()
            .saveToDefaultCollection(id, ExampleInstance(id = id, description = testCase.name.testName))
            .shouldGet<ExampleInstance>(id) { actual ->
                actual.id shouldBe id
                actual.description shouldBe testCase.name.testName
            }
    }
})
