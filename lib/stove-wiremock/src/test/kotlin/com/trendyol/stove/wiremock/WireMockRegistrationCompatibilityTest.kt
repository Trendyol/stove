package com.trendyol.stove.wiremock

import com.trendyol.stove.system.Stove
import com.trendyol.stove.system.stove
import com.trendyol.stove.wiremock.WireMockSystem.Companion.server
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.maps.shouldContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse.BodyHandlers

class WireMockRegistrationCompatibilityTest :
  FunSpec({
    val client = HttpClient.newHttpClient()

    test("stable and structured registrations retain their distinct policies") {
      val stableUrl = "/registration/stable?draft=true"
      val structuredPath = "/registration/structured"

      stove {
        wiremock {
          mockGet(
            url = stableUrl,
            statusCode = 200,
            metadata = mapOf("source" to "stable"),
            responseHeaders = mapOf("X-Registration" to "stable")
          )
          mockGet(structuredPath) {
            respond {
              status = 200
              header("X-Registration", "structured")
              empty()
            }
          }

          val mappings = server().stubMappings
          val stable = mappings.single { it.request.url == stableUrl }
          val structured = mappings.single { it.request.urlPath == structuredPath }

          stable.id.shouldNotBeNull()
          stable.name shouldBe null
          stable.request.urlPath shouldBe null
          stable.metadata.getString("source") shouldBe "stable"
          stable.metadata.getString(WireMockSystem.STOVE_TEST_ID_KEY) shouldBe Stove.reporter().currentTestId()

          structured.id.shouldNotBeNull()
          structured.name shouldBe "GET $structuredPath"
          structured.request.url shouldBe null
          structured.metadata.getString(WireMockSystem.STOVE_TEST_ID_KEY) shouldBe Stove.reporter().currentTestId()

          val registrationEntries = Stove.reporter()
            .currentTest()
            .entries()
            .filter { it.action.startsWith("Register stub: GET /registration/") }

          registrationEntries.map { it.action } shouldContainExactly listOf(
            "Register stub: GET $stableUrl",
            "Register stub: GET $structuredPath"
          )
          registrationEntries[0].metadata shouldContain (
            WireMockReportMetadataKeys.RESPONSE_HEADERS to mapOf("X-Registration" to "stable")
            )
          registrationEntries[1].metadata shouldContain (
            WireMockReportMetadataKeys.RESPONSE_HEADERS to mapOf("X-Registration" to listOf("structured"))
            )
        }
      }

      val stableResponse = client.send(
        HttpRequest.newBuilder(URI("$WIREMOCK_BASE_URL$stableUrl")).GET().build(),
        BodyHandlers.ofString()
      )
      stableResponse.statusCode() shouldBe 200
      stableResponse.headers().firstValue("Content-Type").orElseThrow() shouldBe "application/json; charset=UTF-8"
      stableResponse.headers().firstValue("X-Registration").orElseThrow() shouldBe "stable"

      val structuredResponse = client.send(
        HttpRequest
          .newBuilder(URI("$WIREMOCK_BASE_URL$structuredPath?ignored=true"))
          .GET()
          .build(),
        BodyHandlers.ofString()
      )
      structuredResponse.statusCode() shouldBe 200
      structuredResponse.headers().firstValue("Content-Type").isEmpty shouldBe true
      structuredResponse.headers().firstValue("X-Registration").orElseThrow() shouldBe "structured"
    }
  })
