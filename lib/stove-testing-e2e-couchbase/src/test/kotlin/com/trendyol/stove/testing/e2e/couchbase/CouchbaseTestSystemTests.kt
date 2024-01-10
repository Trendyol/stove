package com.trendyol.stove.testing.e2e.couchbase

import com.couchbase.client.java.ReactiveCluster
import com.couchbase.client.java.manager.collection.CreateCollectionSettings
import com.trendyol.stove.testing.e2e.database.migrations.*
import com.trendyol.stove.testing.e2e.system.TestSystem
import com.trendyol.stove.testing.e2e.system.TestSystem.Companion.validate
import com.trendyol.stove.testing.e2e.system.abstractions.ApplicationUnderTest
import io.kotest.core.config.AbstractProjectConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.reactive.awaitFirstOrNull
import org.junit.jupiter.api.assertThrows
import org.slf4j.*
import java.time.Duration
import java.util.*

const val TEST_BUCKET = "test-couchbase-bucket"

class Setup : AbstractProjectConfig() {
    override suspend fun beforeProject(): Unit =
        TestSystem {}
            .with {
                couchbase {
                    CouchbaseSystemOptions(
                        defaultBucket = TEST_BUCKET,
                        containerOptions = ContainerOptions(
                            imageVersion = "latest"
                        )
                    )
                        .migrations {
                            register<DefaultMigration>()
                        }
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

class DefaultMigration : DatabaseMigration<ReactiveCluster> {
    private val logger: Logger = LoggerFactory.getLogger(javaClass)

    override val order: Int = MigrationPriority.HIGHEST.value

    override suspend fun execute(connection: ReactiveCluster) {
        connection
            .bucket(TEST_BUCKET)
            .collections().createCollection("_default", "another", CreateCollectionSettings.createCollectionSettings())
            .awaitFirstOrNull()

        connection.bucket(TEST_BUCKET).waitUntilReady(Duration.ofSeconds(30)).awaitFirstOrNull()

        logger.info("default migration is executed")
    }
}

class CouchbaseTestSystemUsesDslTests : FunSpec({

    data class ExampleInstance(
        val id: String,
        val description: String
    )

    test("should save and get") {
        val id = UUID.randomUUID().toString()
        val anotherCollectionName = "another"
        validate {
            couchbase {
                saveToDefaultCollection(id, ExampleInstance(id = id, description = testCase.name.testName))
                save(anotherCollectionName, id = id, ExampleInstance(id = id, description = testCase.name.testName))
                shouldGet<ExampleInstance>(id) { actual ->
                    actual.id shouldBe id
                    actual.description shouldBe testCase.name.testName
                }
                shouldGet<ExampleInstance>(anotherCollectionName, id) { actual ->
                    actual.id shouldBe id
                    actual.description shouldBe testCase.name.testName
                }
            }
        }
    }

    test("should not get when document does not exist") {
        val id = UUID.randomUUID().toString()
        val notExistDocId = UUID.randomUUID().toString()
        validate {
            couchbase {
                saveToDefaultCollection(id, ExampleInstance(id = id, description = testCase.name.testName))
                shouldGet<ExampleInstance>(id) { actual ->
                    actual.id shouldBe id
                    actual.description shouldBe testCase.name.testName
                }
                shouldNotExist(notExistDocId)
            }
        }
    }

    test("should throw assertion exception when document exist") {
        val id = UUID.randomUUID().toString()
        validate {
            couchbase {
                saveToDefaultCollection(id, ExampleInstance(id = id, description = testCase.name.testName))
                shouldGet<ExampleInstance>(id) { actual ->
                    actual.id shouldBe id
                    actual.description shouldBe testCase.name.testName
                }
                assertThrows<AssertionError> { shouldNotExist(id) }
            }
        }
    }
})
