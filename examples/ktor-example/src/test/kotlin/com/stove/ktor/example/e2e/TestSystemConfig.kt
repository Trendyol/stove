package com.stove.ktor.example.e2e

import com.trendol.stove.testing.e2e.rdbms.postgres.PostgresqlOptions
import com.trendol.stove.testing.e2e.rdbms.postgres.postgresql
import com.trendyol.stove.testing.e2e.http.httpClient
import com.trendyol.stove.testing.e2e.ktor
import com.trendyol.stove.testing.e2e.system.TestSystem
import com.trendyol.stove.testing.e2e.wiremock.WireMockSystemOptions
import com.trendyol.stove.testing.e2e.wiremock.wiremock
import io.kotest.core.config.AbstractProjectConfig
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class TestSystemConfig : AbstractProjectConfig() {
    private val logger: Logger = LoggerFactory.getLogger("WireMockMonitor")

    override suspend fun beforeProject() =
        TestSystem(baseUrl = "http://localhost:8080")
            .with {
                httpClient()
                postgresql {
                    PostgresqlOptions(configureExposedConfiguration = { cfg ->
                        listOf(
                            "database.jdbcUrl=${cfg.jdbcUrl}",
                            "database.host=${cfg.host}",
                            "database.port=${cfg.port}",
                            "database.databaseName=${cfg.database}",
                            "database.username=${cfg.username}",
                            "database.password=${cfg.password}"
                        )
                    })
                }
                wiremock {
                    WireMockSystemOptions(
                        port = 9090,
                        removeStubAfterRequestMatched = true,
                        afterRequest = { e, _ ->
                            logger.info(e.request.toString())
                        }
                    )
                }
                ktor(
                    withParameters =
                        listOf(
                            "ktor.server.port=8001"
                        ),
                    runner = { parameters ->
                        stove.ktor.example.run(parameters) {
                            addTestSystemDependencies()
                        }
                    }
                )
            }.run()

    override suspend fun afterProject() {
        TestSystem.stop()
    }
}
