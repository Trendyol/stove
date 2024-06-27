package com.trendyol.stove.testing.e2e.http

import arrow.core.*
import com.github.tomakehurst.wiremock.client.WireMock.*
import com.github.tomakehurst.wiremock.matching.MultipartValuePattern
import com.trendyol.stove.testing.e2e.serialization.StoveObjectMapper
import com.trendyol.stove.testing.e2e.system.TestSystem
import com.trendyol.stove.testing.e2e.system.abstractions.ApplicationUnderTest
import com.trendyol.stove.testing.e2e.wiremock.*
import io.kotest.core.config.AbstractProjectConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.Instant
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
    TestSystem()
      .with {
        httpClient {
          HttpClientSystemOptions(
            baseUrl = "http://localhost:8086",
            objectMapper = StoveObjectMapper.byConfiguring {
              findAndRegisterModules()
            }
          )
        }

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

        get<List<TestDto>>("/get-many") { actual ->
          actual[0] shouldBe TestDto(expectedGetDtoName)
        }
      }
    }
  }

  test("getResponse and expect body") {
    val expectedGetDtoName = UUID.randomUUID().toString()
    TestSystem.validate {
      wiremock {
        mockGet("/get", 200, responseBody = TestDto(expectedGetDtoName).some())
      }

      http {
        getResponse<TestDto>("/get") { actual ->
          actual.body().name shouldBe expectedGetDtoName
        }
      }
    }
  }

  test("getResponse and expect bodiless") {
    val expectedGetDtoName = UUID.randomUUID().toString()
    TestSystem.validate {
      wiremock {
        mockGet("/get", 200, responseBody = TestDto(expectedGetDtoName).some())
      }

      http {
        getResponse("/get") { actual ->
          actual.status shouldBe 200
          actual::class shouldBe StoveHttpResponse.Bodiless::class
        }
      }
    }
  }

  test("put and expect body") {
    val expectedPutDtoName = UUID.randomUUID().toString()
    TestSystem.validate {
      wiremock {
        mockPut("/put-with-response-body", 200, None, responseBody = TestDto(expectedPutDtoName).some())
      }

      http {
        putAndExpectBody<TestDto>("/put-with-response-body") { actual ->
          actual.body().name shouldBe expectedPutDtoName
        }
      }
    }
  }

  test("post and expect body") {
    val expectedPostDtoName = UUID.randomUUID().toString()
    TestSystem.validate {
      wiremock {
        mockPost("/post-with-response-body", 200, None, responseBody = TestDto(expectedPostDtoName).some())
      }

      http {
        postAndExpectBody<TestDto>("/post-with-response-body") { actual ->
          actual.body().name shouldBe expectedPostDtoName
        }
      }
    }
  }

  test("get with query params should work") {
    val expectedGetDtoName = UUID.randomUUID().toString()
    TestSystem.validate {
      wiremock {
        mockGet("/get?param=1", 200, responseBody = TestDto(expectedGetDtoName).some())
      }

      http {
        get<TestDto>("/get", queryParams = mapOf("param" to "1")) { actual ->
          actual.name shouldBe expectedGetDtoName
        }
      }
    }
  }

  test("multipart post should work") {
    val expectedPostDtoName = UUID.randomUUID().toString()
    TestSystem.validate {
      wiremock {
        mockPostConfigure("/post-with-multipart") { req, _ ->
          req.withMultipartRequestBody(
            aMultipart()
              .matchingType(MultipartValuePattern.MatchingType.ANY)
              .withHeader("Content-Disposition", equalTo("form-data; name=name"))
              .withBody(equalTo(expectedPostDtoName))
          )
          req.withMultipartRequestBody(
            aMultipart()
              .matchingType(MultipartValuePattern.MatchingType.ANY)
              .withHeader("Content-Disposition", equalTo("form-data; name=file; filename=file.png"))
              .withBody(equalTo("file"))
          )
          req.willReturn(aResponse().withStatus(200).withBody("hoi!"))
        }
      }

      http {
        postMultipartAndExpectResponse<String>(
          "/post-with-multipart",
          body = listOf(
            StoveMultiPartContent.Text("name", expectedPostDtoName),
            StoveMultiPartContent.File(
              param = "file",
              fileName = "file.png",
              content = "file".toByteArray(),
              contentType = "application/octet-stream"
            )
          )
        ) { actual ->
          actual.body() shouldBe "hoi!"
          actual.status shouldBe 200
        }
      }
    }
  }

  test("java time instant should work") {
    val expectedGetDtoName = UUID.randomUUID().toString()
    TestSystem.validate {
      wiremock {
        mockGet("/get", 200, responseBody = TestDtoWithInstant(expectedGetDtoName, Instant.now()).some())
      }

      http {
        get<TestDtoWithInstant>("/get") { actual ->
          actual.name shouldBe expectedGetDtoName
        }
      }
    }
  }

  test("keep path segments as is") {
    val expectedGetDtoName = UUID.randomUUID().toString()
    TestSystem.validate {
      wiremock {
        mockGet("/get?path=1", 200, responseBody = TestDto(expectedGetDtoName).some())
      }

      http {
        get<TestDto>("/get?path=1") { actual ->
          actual.name shouldBe expectedGetDtoName
        }
      }
    }
  }
})

data class TestDto(
  val name: String
)

data class TestDtoWithInstant(
  val name: String,
  val instant: Instant
)
