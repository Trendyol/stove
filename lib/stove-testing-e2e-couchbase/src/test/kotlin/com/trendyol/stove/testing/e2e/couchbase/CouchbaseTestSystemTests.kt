package com.trendyol.stove.testing.e2e.couchbase

import com.couchbase.client.java.ReactiveCluster
import com.couchbase.client.java.manager.collection.CollectionSpec
import com.trendyol.stove.testing.e2e.couchbase.CouchbaseSystem.Companion.shouldGet
import com.trendyol.stove.testing.e2e.database.DocumentDatabaseSystem.Companion.shouldGet
import com.trendyol.stove.testing.e2e.database.migrations.DatabaseMigration
import com.trendyol.stove.testing.e2e.system.TestSystem
import com.trendyol.stove.testing.e2e.system.abstractions.ApplicationUnderTest
import io.kotest.core.config.AbstractProjectConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.reactive.awaitFirstOrNull
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.UUID

const val testBucket = "test-couchbase-bucket"

class Setup : AbstractProjectConfig() {
    override suspend fun beforeProject() {
        TestSystem {
            keepDependenciesRunning()
        }.withCouchbase(
            CouchbaseSystemOptions(defaultBucket = testBucket).migrations {
                register<DefaultMigration>()
            }
        ).applicationUnderTest(NoOpApplication()).run()
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

class DefaultMigration : DatabaseMigration<ReactiveCluster> {
    private val logger: Logger = LoggerFactory.getLogger(javaClass)
    override suspend fun execute(connection: ReactiveCluster) {
        connection
          .bucket(testBucket)
          .collections().createCollection(CollectionSpec.create("another", "_default"))
          .awaitFirstOrNull()

        connection.bucket(testBucket).waitUntilReady(Duration.ofSeconds(30)).awaitFirstOrNull()

        logger.info("default migration is executed")
    }
}

class CouchbaseTestSystemTests : FunSpec({

    data class ExampleInstance(
        val id: String,
        val description: String,
    )

    test("should save and get") {
        val id = UUID.randomUUID().toString()
        val anotherCollectionName = "another"
        TestSystem.instance
          .couchbase()
          .saveToDefaultCollection(id, ExampleInstance(id = id, description = testCase.name.testName))
          .save(anotherCollectionName, id = id, ExampleInstance(id = id, description = testCase.name.testName))
          .shouldGet<ExampleInstance>(id) { actual ->
              actual.id shouldBe id
              actual.description shouldBe testCase.name.testName
          }
          .then().couchbase()
          .shouldGet<ExampleInstance>(anotherCollectionName, id) { actual ->
              actual.id shouldBe id
              actual.description shouldBe testCase.name.testName
          }
    }
})
