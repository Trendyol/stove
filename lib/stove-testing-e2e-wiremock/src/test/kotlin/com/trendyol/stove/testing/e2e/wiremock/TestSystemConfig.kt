package com.trendyol.stove.testing.e2e.wiremock

import com.trendyol.stove.testing.e2e.system.TestSystem
import com.trendyol.stove.testing.e2e.system.abstractions.ApplicationUnderTest
import io.kotest.core.config.AbstractProjectConfig

class TestSystemConfig : AbstractProjectConfig() {

    override suspend fun beforeProject() {
        TestSystem()
            .withWireMock(
                port = 9098,
                WireMockSystemOptions(
                    removeStubAfterRequestMatched = true
                )
            )
            .applicationUnderTest(object : ApplicationUnderTest<Unit> {
                override suspend fun start(configurations: List<String>) {
                }

                override suspend fun stop() {
                }
            }).run()
    }

    override suspend fun afterProject() {
        TestSystem.instance.close()
    }
}
