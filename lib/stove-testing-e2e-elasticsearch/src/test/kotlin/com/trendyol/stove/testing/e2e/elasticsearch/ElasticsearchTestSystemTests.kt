package com.trendyol.stove.testing.e2e.elasticsearch

import co.elastic.clients.elasticsearch._types.query_dsl.QueryBuilders
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.trendyol.stove.testing.e2e.database.DatabaseSystem.Companion.shouldQuery
import com.trendyol.stove.testing.e2e.database.DocumentDatabaseSystem.Companion.shouldGet
import com.trendyol.stove.testing.e2e.elasticsearch.ElasticsearchSystem.Companion.shouldQuery
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

    test("should save 2 documents with the same description, then delete first one and query by description") {
        val desc = "some description"
        val exampleInstance1 = ExampleInstance("1", desc)
        val exampleInstance2 = ExampleInstance("2", desc)
        val queryByDesc = QueryBuilders.term().field("description.keyword").value(desc).queryName("query_name").build()
        val queryAsString = queryByDesc.asJsonString()

        TestSystem.instance
            .elasticsearch()
            .save(exampleInstance1.id, exampleInstance1)
            .save(exampleInstance2.id, exampleInstance2)
            .shouldQuery<ExampleInstance>(queryByDesc._toQuery()) {
                it.size shouldBe 2
            }
            .shouldDelete(exampleInstance1.id)
            .shouldGet<ExampleInstance>(exampleInstance2.id) {}
            .shouldQuery<ExampleInstance>(queryAsString) {
                it.size shouldBe 1
            }
    }
})
