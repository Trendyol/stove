package com.trendyol.stove.testing.e2e.elasticsearch

import com.trendyol.stove.testing.e2e.system.TestSystem
import com.trendyol.stove.testing.e2e.system.abstractions.ApplicationUnderTest
import io.kotest.core.config.AbstractProjectConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class Setup : AbstractProjectConfig() {
    override suspend fun beforeProject() {
        TestSystem()
            .withElasticsearch()
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
        val expected = ExampleInstance("1", "")
        TestSystem.instance
            .elasticsearch()
            .save("index-name", expected.id, expected)
            .shouldGets("index-name", expected.id, ExampleInstance::class) { actual ->
                actual.id shouldBe expected.id
            }
    }
})
