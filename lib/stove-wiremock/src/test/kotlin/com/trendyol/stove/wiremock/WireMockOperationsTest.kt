package com.trendyol.stove.wiremock

import com.github.tomakehurst.wiremock.client.WireMock.*
import com.github.tomakehurst.wiremock.matching.ContainsPattern
import com.trendyol.stove.system.stove
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.net.URI
import java.net.http.*
import java.net.http.HttpRequest.BodyPublishers
import java.net.http.HttpResponse.BodyHandlers

class WireMockOperationsTest :
  FunSpec({

    /*
     * Configures a POST request mock using [WireMockSystem.mockPostConfigure].
     *
     * @param urlMatcher A [UrlPattern] used to match the request URL. Defaults to [urlEqualTo] with the provided [url].
     *
     * This test demonstrates how to use the default URL pattern for [WireMockSystem.mockPostConfigure].
     */
    test("Wiremock mockPostConfigure should mock urls with urlEqualTo(url) pattern in default") {
      val url = "/post-url"
      val client = HttpClient.newBuilder().build()
      val reqBuilder = HttpRequest
        .newBuilder(URI("http://localhost:9098/$url"))
        .header("Content-Type", "application/json")

      stove {
        wiremock {
          mockPostConfigure("/$url") { req, _ ->
            req
              .withRequestBody(equalTo("request2"))
              .withHeader("Content-Type", ContainsPattern("application/json"))
              .willReturn(
                aResponse()
                  .withBody("response2")
                  .withStatus(200)
                  .withHeader("Content-Type", "application/json; charset=UTF-8")
              )
          }
          mockPostConfigure("/$url") { req, _ ->
            req
              .withRequestBody(equalTo("request1"))
              .withHeader("Content-Type", ContainsPattern("application/json"))
              .willReturn(
                aResponse()
                  .withBody("response1")
                  .withStatus(200)
                  .withHeader("Content-Type", "application/json; charset=UTF-8")
              )
          }
        }
      }

      val request2 = reqBuilder.POST(BodyPublishers.ofString("request2")).build()
      val response2 = client.send(request2, BodyHandlers.ofString())
      response2.body() shouldBe "response2"

      val request1 = reqBuilder.POST(BodyPublishers.ofString("request1")).build()
      val response1 = client.send(request1, BodyHandlers.ofString())
      response1.body() shouldBe "response1"
    }

    /*
     * Configures a POST request mock using [WireMockSystem.mockPostConfigure].
     *
     * @param urlMatcher A [UrlPattern] used to match the request URL. Defaults to [urlEqualTo] with the provided [url].
     *
     * This test demonstrates how to configure and override the default URL matcher for [WireMockSystem.mockPostConfigure].
     */
    test("Wiremock mockPostConfigure should accept overridden urlMatcher") {
      val url = "categories/createCategory"
      val client = HttpClient.newBuilder().build()
      val reqBuilder = HttpRequest
        .newBuilder(URI("http://localhost:9098/$url"))
        .header("Content-Type", "application/json")

      stove {
        wiremock {
          mockPostConfigure("/categories/.*", { urlPathMatching(it) }) { req, _ ->
            req
              .withRequestBody(equalTo("request2"))
              .withHeader("Content-Type", ContainsPattern("application/json"))
              .willReturn(
                aResponse()
                  .withBody("response2")
                  .withStatus(200)
                  .withHeader("Content-Type", "application/json; charset=UTF-8")
              )
          }
          mockPostConfigure("/categories/.*", { urlPathMatching(it) }) { req, _ ->
            req
              .withRequestBody(equalTo("request1"))
              .withHeader("Content-Type", ContainsPattern("application/json"))
              .willReturn(
                aResponse()
                  .withBody("response1")
                  .withStatus(200)
                  .withHeader("Content-Type", "application/json; charset=UTF-8")
              )
          }
        }
      }

      val request2 = reqBuilder.POST(BodyPublishers.ofString("request2")).build()
      val response2 = client.send(request2, BodyHandlers.ofString())
      response2.body() shouldBe "response2"

      val request1 = reqBuilder.POST(BodyPublishers.ofString("request1")).build()
      val response1 = client.send(request1, BodyHandlers.ofString())
      response1.body() shouldBe "response1"
    }

    /*
     * Configures a POST request mock using [WireMockSystem.mockGetConfigure].
     *
     * @param urlMatcher A [UrlPattern] used to match the request URL. Defaults to [urlEqualTo] with the provided [url].
     *
     * This test demonstrates how to use the default URL pattern for [WireMockSystem.mockGetConfigure].
     */
    test("Wiremock mockGetConfigure should mock urls with urlEqualTo(url) pattern in default") {
      val client = HttpClient.newBuilder().build()
      var id = 1
      var active = true
      stove {
        wiremock {
          mockGetConfigure("/suppliers/1?active=true") { req, _ ->
            req
              .withHeader("Content-Type", ContainsPattern("application/json"))
              .willReturn(
                aResponse()
                  .withBody("Supplier1Response")
                  .withStatus(200)
                  .withHeader("Content-Type", "application/json; charset=UTF-8")
              )
          }
          mockGetConfigure("/suppliers/2?active=false") { req, _ ->
            req
              .withHeader("Content-Type", ContainsPattern("application/json"))
              .willReturn(
                aResponse()
                  .withBody("Supplier2Response")
                  .withStatus(200)
                  .withHeader("Content-Type", "application/json; charset=UTF-8")
              )
          }
        }
      }

      val uri = URI.create("http://localhost:9098/suppliers/$id?active=$active")
      val reqBuilder = HttpRequest
        .newBuilder(uri)
        .header("Content-Type", "application/json")

      val request2 = reqBuilder.GET().build()
      val response2 = client.send(request2, BodyHandlers.ofString())
      response2.body() shouldBe "Supplier1Response"

      id = 2
      active = false
      val uri2 = URI.create("http://localhost:9098/suppliers/$id?active=$active")
      val reqBuilder2 = HttpRequest
        .newBuilder(uri2)
        .header("Content-Type", "application/json")
      val request1 = reqBuilder2.GET().build()
      val response1 = client.send(request1, BodyHandlers.ofString())
      response1.body() shouldBe "Supplier2Response"
    }

    /*
     * Configures a POST request mock using [WireMockSystem.mockGetConfigure].
     *
     * @param urlMatcher A [UrlPattern] used to match the request URL. Defaults to [urlEqualTo] with the provided [url].
     *
     * This test demonstrates how to configure and override the default URL matcher for [WireMockSystem.mockGetConfigure].
     */
    test("Wiremock mockGetConfigure should accept overridden urlMatcher") {
      val client = HttpClient.newBuilder().build()
      stove {
        wiremock {
          mockGetConfigure("/suppliers/1.*", { urlPathMatching(it) }) { req, _ ->
            req
              .withHeader("Content-Type", ContainsPattern("application/json"))
              .withQueryParam("active", matching("true|false"))
              .willReturn(
                aResponse()
                  .withBody("Supplier1Response")
                  .withStatus(200)
                  .withHeader("Content-Type", "application/json; charset=UTF-8")
              )
          }
          mockGetConfigure("/suppliers/2.*", { urlPathMatching(it) }) { req, _ ->
            req
              .withHeader("Content-Type", ContainsPattern("application/json"))
              .withQueryParam("active", matching("true|false"))
              .willReturn(
                aResponse()
                  .withBody("Supplier2Response")
                  .withStatus(200)
                  .withHeader("Content-Type", "application/json; charset=UTF-8")
              )
          }
        }
      }

      var id = 1
      var active = true
      val uri1 = URI.create("http://localhost:9098/suppliers/$id?active=$active")
      val request1 = HttpRequest
        .newBuilder(uri1)
        .header("Content-Type", "application/json")
        .GET()
        .build()
      val response1 = client.send(request1, BodyHandlers.ofString())
      response1.body() shouldBe "Supplier1Response"

      id = 2
      active = false
      val uri2 = URI.create("http://localhost:9098/suppliers/$id?active=$active")
      val request2 = HttpRequest
        .newBuilder(uri2)
        .header("Content-Type", "application/json")
        .GET()
        .build()
      val response2 = client.send(request2, BodyHandlers.ofString())
      response2.body() shouldBe "Supplier2Response"
      stove {
        wiremock {
          mockGetConfigure("/suppliers/2.*", { urlPathMatching(it) }) { req, _ ->
            req
              .withHeader("Content-Type", ContainsPattern("application/json"))
              .withQueryParam("active", matching("true|false"))
              .willReturn(
                aResponse()
                  .withBody("Supplier2Response")
                  .withStatus(200)
                  .withHeader("Content-Type", "application/json; charset=UTF-8")
              )
          }
        }
      }
      active = true
      val uri3 = URI.create("http://localhost:9098/suppliers/$id?active=$active")
      val request3 = HttpRequest
        .newBuilder(uri3)
        .header("Content-Type", "application/json")
        .GET()
        .build()
      val response3 = client.send(request3, BodyHandlers.ofString())
      response3.body() shouldBe "Supplier2Response"
    }

    /*
     * Configures a POST request mock using [WireMockSystem.mockPutConfigure].
     *
     * @param urlMatcher A [UrlPattern] used to match the request URL. Defaults to [urlEqualTo] with the provided [url].
     *
     * This test demonstrates how to use the default URL pattern for [WireMockSystem.mockPutConfigure].
     */
    test("Wiremock mockPutConfigure should accept default urlMatcher") {
      val client = HttpClient.newBuilder().build()
      stove {
        wiremock {
          mockPutConfigure("/resources/1") { req, _ ->
            req
              .withHeader("Content-Type", ContainsPattern("application/json"))
              .willReturn(
                aResponse()
                  .withBody("PutResource1")
                  .withStatus(200)
                  .withHeader("Content-Type", "application/json; charset=UTF-8")
              )
          }
        }
      }

      val uri = URI.create("http://localhost:9098/resources/1")
      val reqBuilder = HttpRequest
        .newBuilder(uri)
        .header("Content-Type", "application/json")
        .PUT(BodyPublishers.ofString("{\"name\":\"test\"}"))

      val request = reqBuilder.build()
      val response = client.send(request, BodyHandlers.ofString())
      response.body() shouldBe "PutResource1"
    }

    /*
     * Configures a POST request mock using [WireMockSystem.mockPutConfigure].
     *
     * @param urlMatcher A [UrlPattern] used to match the request URL. Defaults to [urlEqualTo] with the provided [url].
     *
     * This test demonstrates how to configure and override the default URL matcher for [WireMockSystem.mockPutConfigure].
     */
    test("Wiremock mockPutConfigure should accept overridden urlMatcher") {
      val client = HttpClient.newBuilder().build()
      stove {
        wiremock {
          mockPutConfigure("/resources/.*", { urlPathMatching(it) }) { req, _ ->
            req
              .withHeader("Content-Type", ContainsPattern("application/json"))
              .willReturn(
                aResponse()
                  .withBody("PutResourceMatched")
                  .withStatus(200)
                  .withHeader("Content-Type", "application/json; charset=UTF-8")
              )
          }
        }
      }

      val uri = URI.create("http://localhost:9098/resources/123")
      val reqBuilder = HttpRequest
        .newBuilder(uri)
        .header("Content-Type", "application/json")
        .PUT(BodyPublishers.ofString("{\"name\":\"test\"}"))

      val request = reqBuilder.build()
      val response = client.send(request, BodyHandlers.ofString())
      response.body() shouldBe "PutResourceMatched"
    }

    /*
     * Configures a POST request mock using [WireMockSystem.mockDeleteConfigure].
     *
     * @param urlMatcher A [UrlPattern] used to match the request URL. Defaults to [urlEqualTo] with the provided [url].
     *
     * This test demonstrates how to use the default URL pattern for [WireMockSystem.mockDeleteConfigure].
     */
    test("Wiremock mockDeleteConfigure should accept default urlMatcher") {
      val client = HttpClient.newBuilder().build()
      stove {
        wiremock {
          mockDeleteConfigure("/resources/1") { req, _ ->
            req
              .withHeader("Authorization", equalTo("Bearer token"))
              .willReturn(
                aResponse().withStatus(204)
              )
          }
        }

        val uri = URI.create("http://localhost:9098/resources/1")
        val reqBuilder = HttpRequest
          .newBuilder(uri)
          .header("Authorization", "Bearer token")
          .DELETE()

        val request = reqBuilder.build()
        val response = client.send(request, BodyHandlers.ofString())

        response.statusCode() shouldBe 204
      }
    }

    /*
     * Configures a POST request mock using [WireMockSystem.mockDeleteConfigure].
     *
     * @param urlMatcher A [UrlPattern] used to match the request URL. Defaults to [urlEqualTo] with the provided [url].
     *
     * This test demonstrates how to configure and override the default URL matcher for [WireMockSystem.mockDeleteConfigure].
     */
    test("Wiremock mockDeleteConfigure should accept overridden urlMatcher") {
      val client = HttpClient.newBuilder().build()
      stove {
        wiremock {
          mockDeleteConfigure("/resources/.*", { urlPathMatching(it) }) { req, _ ->
            req
              .withHeader("Authorization", equalTo("Bearer token"))
              .willReturn(
                aResponse().withStatus(204)
              )
          }
        }

        val uri = URI.create("http://localhost:9098/resources/123")
        val reqBuilder = HttpRequest
          .newBuilder(uri)
          .header("Authorization", "Bearer token")
          .DELETE()

        val request = reqBuilder.build()
        val response = client.send(request, BodyHandlers.ofString())

        response.statusCode() shouldBe 204
      }
    }

    /*
     * Configures a POST request mock using [WireMockSystem.mockPatchConfigure].
     *
     * @param urlMatcher A [UrlPattern] used to match the request URL. Defaults to [urlEqualTo] with the provided [url].
     *
     * This test demonstrates how to use the default URL pattern for [WireMockSystem.mockPatchConfigure].
     */
    test("Wiremock mockPatchConfigure should accept default urlMatcher") {
      val client = HttpClient.newBuilder().build()
      stove {
        wiremock {
          mockPatchConfigure("/resources/1") { req, _ ->
            req
              .withHeader("Content-Type", ContainsPattern("application/json"))
              .willReturn(
                aResponse()
                  .withBody("PatchResource1")
                  .withStatus(200)
                  .withHeader("Content-Type", "application/json; charset=UTF-8")
              )
          }
        }
      }

      val uri = URI.create("http://localhost:9098/resources/1")
      val reqBuilder = HttpRequest
        .newBuilder(uri)
        .header("Content-Type", "application/json")
        .method("PATCH", BodyPublishers.ofString("{\"name\":\"updated\"}"))

      val request = reqBuilder.build()
      val response = client.send(request, BodyHandlers.ofString())
      response.body() shouldBe "PatchResource1"
    }

    /*
     * Configures a POST request mock using [WireMockSystem.mockPatchConfigure].
     *
     * @param urlMatcher A [UrlPattern] used to match the request URL. Defaults to [urlEqualTo] with the provided [url].
     *
     * This test demonstrates how to configure and override the default URL matcher for [WireMockSystem.mockPatchConfigure].
     */
    test("Wiremock mockPatchConfigure should accept overridden urlMatcher") {
      val client = HttpClient.newBuilder().build()
      stove {
        wiremock {
          mockPatchConfigure("/resources/.*", { (urlPathMatching(it)) }) { req, _ ->
            req
              .withHeader("Content-Type", ContainsPattern("application/json"))
              .willReturn(
                aResponse()
                  .withBody("PatchResourceMatched")
                  .withStatus(200)
                  .withHeader("Content-Type", "application/json; charset=UTF-8")
              )
          }
        }
      }

      val uri = URI.create("http://localhost:9098/resources/123")
      val reqBuilder = HttpRequest
        .newBuilder(uri)
        .header("Content-Type", "application/json")
        .method("PATCH", BodyPublishers.ofString("{\"name\":\"updated\"}"))

      val request = reqBuilder.build()
      val response = client.send(request, BodyHandlers.ofString())
      response.body() shouldBe "PatchResourceMatched"
    }

    /*
     * Configures a POST request mock using [WireMockSystem.mockHeadConfigure].
     *
     * @param urlMatcher A [UrlPattern] used to match the request URL. Defaults to [urlEqualTo] with the provided [url].
     *
     * This test demonstrates how to use the default URL pattern for [WireMockSystem.mockHeadConfigure].
     */
    test("Wiremock mockHeadConfigure should accept default urlMatcher") {
      val client = HttpClient.newBuilder().build()
      stove {
        wiremock {
          mockHeadConfigure("/resources/1") { req, _ ->
            req
              .withHeader("Authorization", equalTo("Bearer token"))
              .willReturn(
                aResponse()
                  .withStatus(200)
                  .withHeader("X-Custom-Header", "CustomValue")
              )
          }
        }
      }

      val uri = URI.create("http://localhost:9098/resources/1")
      val reqBuilder = HttpRequest
        .newBuilder(uri)
        .header("Authorization", "Bearer token")
        .method("HEAD", BodyPublishers.noBody())

      val request = reqBuilder.build()
      val response = client.send(request, BodyHandlers.ofString())
      response.statusCode() shouldBe 200
      response.headers().firstValue("X-Custom-Header").orElse("") shouldBe "CustomValue"
    }

    /*
     * Configures a POST request mock using [WireMockSystem.mockHeadConfigure].
     *
     * @param urlMatcher A [UrlPattern] used to match the request URL. Defaults to [urlEqualTo] with the provided [url].
     *
     * This test demonstrates how to configure and override the default URL matcher for [WireMockSystem.mockHeadConfigure].
     */
    test("Wiremock mockHeadConfigure should accept overridden urlMatcher") {
      val client = HttpClient.newBuilder().build()
      stove {
        wiremock {
          mockHeadConfigure("/resources/.*", { urlPathMatching(it) }) { req, _ ->
            req
              .withHeader("Authorization", equalTo("Bearer token"))
              .willReturn(
                aResponse()
                  .withStatus(200)
                  .withHeader("X-Overridden-Header", "OverriddenValue")
              )
          }
        }
      }

      val uri = URI.create("http://localhost:9098/resources/123")
      val reqBuilder = HttpRequest
        .newBuilder(uri)
        .header("Authorization", "Bearer token")
        .method("HEAD", BodyPublishers.noBody())

      val request = reqBuilder.build()
      val response = client.send(request, BodyHandlers.ofString())
      response.statusCode() shouldBe 200
      response.headers().firstValue("X-Overridden-Header").orElse("") shouldBe "OverriddenValue"
    }
  })
