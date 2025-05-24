package com.trendyol.stove.testing.e2e.http

import arrow.core.*
import com.github.tomakehurst.wiremock.client.WireMock
import com.github.tomakehurst.wiremock.client.WireMock.*
import com.github.tomakehurst.wiremock.matching.MultipartValuePattern
import com.trendyol.stove.ConsoleSpec
import com.trendyol.stove.testing.e2e.http.HttpSystem.Companion.client
import com.trendyol.stove.testing.e2e.system.TestSystem
import com.trendyol.stove.testing.e2e.system.abstractions.ApplicationUnderTest
import com.trendyol.stove.testing.e2e.wiremock.*
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.config.AbstractProjectConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.*
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.flow.toList
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

class Stove : AbstractProjectConfig() {
  override suspend fun beforeProject(): Unit =
    TestSystem()
      .with {
        httpClient {
          HttpClientSystemOptions(
            baseUrl = "http://localhost:8086"
          )
        }

        wiremock {
          WireMockSystemOptions(8086)
        }

        applicationUnderTest(NoApplication())
      }.run()

  override suspend fun afterProject(): Unit = TestSystem.stop()
}

class HttpSystemTests :
  FunSpec({
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

    test("PATCH and expect bodiless/JSON response") {
      val expectedPatchDtoName = UUID.randomUUID().toString()
      TestSystem.validate {
        wiremock {
          mockPatch("/patch-with-response-body", 200, None, responseBody = TestDto(expectedPatchDtoName).some())
          mockPatch("/patch-without-response-body", 200, None, responseBody = None)
        }

        http {

          patchAndExpectBodilessResponse("/patch-without-response-body", None, None) { actual ->
            actual.status shouldBe 200
          }
          patchAndExpectJson<TestDto>("/patch-with-response-body") { actual ->
            actual.name shouldBe expectedPatchDtoName
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

    test("patch and expect body") {
      val expectedPatchDtoName = UUID.randomUUID().toString()
      TestSystem.validate {
        wiremock {
          mockPatch("/patch-with-response-body", 200, None, responseBody = TestDto(expectedPatchDtoName).some())
        }

        http {
          patchAndExpectBody<TestDto>("/patch-with-response-body") { actual ->
            actual.body().name shouldBe expectedPatchDtoName
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

          client() shouldNotBe null

          client { baseUrl ->
            val resp = get(
              baseUrl
                .apply {
                  path("/get")
                  parameters.append("path", "1")
                }.build()
            )
            resp.status shouldBe HttpStatusCode.OK
          }
        }
      }
    }

    test("behavioural tests") {
      val expectedGetDtoName = UUID.randomUUID().toString()
      TestSystem.validate {
        wiremock {
          behaviourFor("/get-behaviour", WireMock::get) {
            initially {
              aResponse()
                .withStatus(503)
                .withBody("Service unavailable")
            }
            then {
              aResponse()
                .withHeader("Content-Type", "application/json")
                .withStatus(200)
                .withBody(it.serialize(TestDto(expectedGetDtoName)))
            }
          }
        }

        http {
          this.getResponse("/get-behaviour") { actual ->
            actual.status shouldBe 503
          }

          get<TestDto>("/get-behaviour") { actual ->
            actual.name shouldBe expectedGetDtoName
          }
        }
      }
    }

    test("if there is no initial step, can not place `then`") {
      TestSystem.validate {
        wiremock {
          behaviourFor("/get-behaviour", WireMock::get) {
            shouldThrow<IllegalStateException> {
              then {
                aResponse()
                  .withHeader("Content-Type", "application/json")
                  .withStatus(200)
                  .withBody(it.serialize(TestDto(UUID.randomUUID().toString())))
              }
            }
          }
        }
      }
    }

    test("should only call initially once") {
      TestSystem.validate {
        wiremock {
          behaviourFor("/get-behaviour", WireMock::get) {
            initially {
              aResponse()
                .withStatus(503)
                .withBody("Service unavailable")
            }
            shouldThrow<IllegalStateException> {
              initially {
                aResponse()
                  .withStatus(503)
                  .withBody("Service unavailable")
              }
            }
          }
        }
      }
    }

    test("serialize to application/x-ndjson") {
      val expectedGetDtoName = UUID.randomUUID().toString()
      val items = (1..10).map { TestDto(expectedGetDtoName) }

      TestSystem.validate {
        wiremock {
          mockGetConfigure("/get-ndjson") { builder, serde ->
            builder.willReturn(
              aResponse()
                .withHeader("Content-Type", "application/x-ndjson")
                .withBody(serde.serializeToStreamJson(items))
            )
          }
        }

        http {
          readJsonStream<TestDto>("/get-ndjson") { actual ->
            val collected = actual.toList()
            collected.size shouldBe 10
            collected.forEach { it.name shouldBe expectedGetDtoName }
          }
        }
      }
    }

    test("get with headers") {
      val expectedGetDtoName = UUID.randomUUID().toString()
      val headers = mapOf("Custom-Header" to "CustomValue")
      TestSystem.validate {
        wiremock {
          mockGet("/get?param=1", 200, responseBody = TestDto(expectedGetDtoName).some(), responseHeaders = headers)
        }

        http {
          getResponse<TestDto>("/get", queryParams = mapOf("param" to "1")) { actual ->
            actual.body().name shouldBe expectedGetDtoName
            actual.headers["custom-header"] shouldBe listOf("CustomValue")
          }
        }
      }
    }
  })

class HttpConsoleTesting :
  ConsoleSpec({ capturedOutput ->
    test("should return error when request bodies do not match") {
      val expectedGetDtoName = UUID.randomUUID().toString()
      TestSystem.validate {
        wiremock {
          mockPost("/post-with-response-body", 200, requestBody = TestDto("lol").some(), responseBody = TestDto(expectedGetDtoName).some())
        }

        shouldThrow<Throwable> {
          http {
            postAndExpectJson<TestDto>("/post-with-response-body2", body = TestDto("no-match").some()) { actual ->
              actual.name shouldBe expectedGetDtoName
            }
          }
        }

        capturedOutput.out shouldContain "[equalToJson]                                              |                                                     <<<<< Body does not match\n"
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
