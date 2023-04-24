package com.trendyol.stove.testing.e2e.http

import com.trendyol.stove.testing.e2e.system.TestSystem
import com.trendyol.stove.testing.e2e.system.abstractions.ApplicationUnderTest
import com.trendyol.stove.testing.e2e.system.abstractions.ExperimentalStoveDsl
import com.trendyol.stove.testing.e2e.wiremock.WireMockSystemOptions
import com.trendyol.stove.testing.e2e.wiremock.wiremock
import io.kotest.core.config.AbstractProjectConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class NoApplication : ApplicationUnderTest<Unit> {

    override suspend fun start(configurations: List<String>) {
        // do nothing
    }

    override suspend fun stop() {
        // do nothing
    }
}

class TestConfig : AbstractProjectConfig() {

    @ExperimentalStoveDsl
    override suspend fun beforeProject(): Unit =
        TestSystem("http://localhost:8086")
            .with {
                httpClient()

                wiremock {
                    WireMockSystemOptions(8086)
                }

                applicationUnderTest(NoApplication())
            }.run()

    override suspend fun afterProject(): Unit = TestSystem.stop()
}

class DefaultHttpSystemTests : FunSpec({
    test("DELETE and expect bodiless response") {
        TestSystem.validate {
            wiremock {
                mockDelete("/delete-success", statusCode = 200)
                mockDelete("/delete-fail", statusCode = 400)
            }

            http {
                deleteAndExpectBodilessResponse("/delete-success") { actual ->
                    actual.status shouldBe 200
                }
                deleteAndExpectBodilessResponse("/delete-fail") { actual ->
                    actual.status shouldBe 400
                }
            }
        }
    }
})
