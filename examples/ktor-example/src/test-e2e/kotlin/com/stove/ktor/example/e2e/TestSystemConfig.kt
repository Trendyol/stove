package com.stove.ktor.example.e2e

import com.trendyol.stove.testing.e2e.http.withDefaultHttp
import com.trendyol.stove.testing.e2e.system.TestSystem
import com.trendyol.stove.testing.e2e.systemUnderTest
import com.trendyol.stove.testing.e2e.wiremock.WireMockSystemOptions
import com.trendyol.stove.testing.e2e.wiremock.withWireMock
import io.kotest.core.config.AbstractProjectConfig
import io.kotest.core.extensions.Extension
import io.kotest.core.listeners.AfterTestListener
import io.kotest.core.test.TestCase
import io.kotest.core.test.TestResult
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import withPostgresql

class TestSystemConfig : AbstractProjectConfig() {

    private val logger: Logger = LoggerFactory.getLogger("WireMockMonitor")
    override suspend fun beforeProject() = TestSystem(baseUrl = "http://localhost:8080").withDefaultHttp().withPostgresql { cfg ->
        listOf(
            "database.jdbcUrl=${cfg.jdbcUrl}",
            "database.host=${cfg.host}",
            "database.port=${cfg.port}",
            "database.databaseName=${cfg.database}",
            "database.username=${cfg.username}",
            "database.password=${cfg.password}",
        )
    }.withWireMock(
        port = 9090,
        WireMockSystemOptions(removeStubAfterRequestMatched = true, afterRequest = { e, _, _ ->
            logger.info(e.request.toString())
        })
    ).systemUnderTest(
        withParameters = listOf(
            "ktor.server.port=8001"
        ),
        runner = { parameters ->
            stove.ktor.example.run(parameters) {
                addTestSystemDependencies()
            }
        }
    ).run()

    override fun extensions(): List<Extension> {
        val listener = object : AfterTestListener {
            override suspend fun afterTest(
                testCase: TestCase,
                result: TestResult,
            ) {
                // TestSystem.instance.wiremock().validate()
            }
        }

        return listOf(listener)
    }

    override suspend fun afterProject() {
        TestSystem.instance.close()
    }
}
