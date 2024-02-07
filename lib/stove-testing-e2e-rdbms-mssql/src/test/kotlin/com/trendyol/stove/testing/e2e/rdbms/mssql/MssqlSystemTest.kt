package com.trendyol.stove.testing.e2e.rdbms.mssql

import com.trendyol.stove.functional.*
import com.trendyol.stove.testing.e2e.database.migrations.*
import com.trendyol.stove.testing.e2e.system.TestSystem
import com.trendyol.stove.testing.e2e.system.TestSystem.Companion.validate
import com.trendyol.stove.testing.e2e.system.abstractions.ApplicationUnderTest
import io.kotest.core.config.AbstractProjectConfig
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.reactive.awaitFirstOrNull
import org.slf4j.*

class Setup : AbstractProjectConfig() {
    override suspend fun beforeProject(): Unit =
        TestSystem()
            .with {
                mssql {
                    MsSqlOptions(
                        applicationName = "test",
                        databaseName = "test",
                        userName = "sa",
                        password = "Password12!",
                        configureContainer = {
                            dockerImageName = "mcr.microsoft.com/mssql/server:latest"
                            this
                        }
                    ).migrations {
                        register<InitialMigration>()
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

class InitialMigration : DatabaseMigration<SqlMigrationContext> {
    private val logger: Logger = LoggerFactory.getLogger(javaClass)

    override val order: Int = MigrationPriority.HIGHEST.value + 1

    override suspend fun execute(connection: SqlMigrationContext) {
        // read the migration file
        val sql = "SELECT 1"
        logger.info("Executing migration: $sql")
        Try {
            connection.executeAsRoot(sql)
        }.recover {
            logger.error("Migration failed", it)
            throw it
        }
        logger.info("Migration executed successfully")
    }
}

class MssqlSystemTests : ShouldSpec({

    should("work") {
        validate {
            mssql {
                ops {
                    use {
                        transaction {
                            val result = select("SELECT 1")
                            result.rowsUpdated.awaitFirstOrNull() shouldBe 1
                        }
                    }
                }
            }
        }
    }
})
