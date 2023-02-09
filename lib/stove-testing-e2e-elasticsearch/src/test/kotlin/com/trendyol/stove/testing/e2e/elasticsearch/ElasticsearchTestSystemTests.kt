package com.trendyol.stove.testing.e2e.elasticsearch

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.trendyol.stove.testing.e2e.database.DocumentDatabaseSystem.Companion.shouldGet
import com.trendyol.stove.testing.e2e.system.TestSystem
import com.trendyol.stove.testing.e2e.system.abstractions.ApplicationUnderTest
import io.kotest.core.config.AbstractProjectConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class Setup : AbstractProjectConfig() {
    override suspend fun beforeProject() {
        TestSystem()
            .withElasticsearch("index-name")
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

    @JsonIgnoreProperties
    data class ExampleInstance(
        val id: String,
        val description: String,
    )
    test("should save and get") {
        val exampleInstance = ExampleInstance("1", "1312")

        TestSystem.instance
            .elasticsearch()
            .save(exampleInstance.id, exampleInstance)
            .shouldGet<ExampleInstance>(exampleInstance.id) {
                it.description shouldBe exampleInstance.description
            }
    }
})
