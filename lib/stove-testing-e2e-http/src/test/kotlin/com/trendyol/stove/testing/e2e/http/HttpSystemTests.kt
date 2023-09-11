package com.trendyol.stove.testing.e2e.http

import arrow.core.None
import arrow.core.some
import com.trendyol.stove.testing.e2e.system.TestSystem
import com.trendyol.stove.testing.e2e.system.abstractions.ApplicationUnderTest
import com.trendyol.stove.testing.e2e.wiremock.WireMockSystemOptions
import com.trendyol.stove.testing.e2e.wiremock.wiremock
import io.kotest.core.config.AbstractProjectConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.*

class NoApplication : ApplicationUnderTest<Unit> {

    override suspend fun start(configurations: List<String>) {
        // do nothing
    }

    override suspend fun stop() {
        // do nothing
    }
}

class TestConfig : AbstractProjectConfig() {

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

class HttpSystemTests : FunSpec({
    test("DELETE and expect bodiless response") {
        TestSystem.validate {
            wiremock {
                mockDelete("/delete-success", statusCode = 200)
                mockDelete("/delete-fail", statusCode = 400)
            }

            http {
                deleteAndExpectBodilessResponse("/delete-success", None) { actual ->
                    actual.status shouldBe 200
                }
                deleteAndExpectBodilessResponse("/delete-fail", None) { actual ->
                    actual.status shouldBe 400
                }
            }
        }
    }

    test("PUT and expect bodiless/JSON response") {
        val expectedPutDtoName = UUID.randomUUID().toString()
        TestSystem.validate {
            wiremock {
                mockPut("/put-with-response-body", 200, None, responseBody = TestDto(expectedPutDtoName).some())
                mockPut("/put-without-response-body", 200, None, responseBody = None)
            }

            http {
                putAndExpectBodilessResponse("/put-without-response-body", None, None) { actual ->
                    actual.status shouldBe 200
                }
                putAndExpectJson<TestDto>("/put-with-response-body") { actual ->
                    actual.name shouldBe expectedPutDtoName
                }
            }
        }
    }

    test("POST and expect bodiless/JSON response") {
        val expectedPOSTDtoName = UUID.randomUUID().toString()
        TestSystem.validate {
            wiremock {
                mockPost("/post-with-response-body", 200, None, responseBody = TestDto(expectedPOSTDtoName).some())
                mockPost("/post-without-response-body", 200, None, responseBody = None)
            }

            http {
                postAndExpectBodilessResponse("/post-without-response-body", None, None) { actual ->
                    actual.status shouldBe 200
                }
                postAndExpectJson<TestDto>("/post-with-response-body") { actual ->
                    actual.name shouldBe expectedPOSTDtoName
                }
            }
        }
    }

    test("GET and expect JSON response") {
        val expectedGetDtoName = UUID.randomUUID().toString()
        TestSystem.validate {
            wiremock {
                mockGet("/get", 200, responseBody = TestDto(expectedGetDtoName).some())
                mockGet("/get-many", 200, responseBody = listOf(TestDto(expectedGetDtoName)).some())
            }

            http {
                get<TestDto>("/get") { actual ->
                    actual.name shouldBe expectedGetDtoName
                }

                getMany<TestDto>("/get-many") { actual ->
                    actual[0] shouldBe TestDto(expectedGetDtoName)
                }
            }
        }
    }
})

data class TestDto(
    val name: String
)
