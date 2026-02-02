package com.trendyol.stove.wiremock

import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Tests for [WireMockExposedConfiguration] data class.
 * These tests are isolated and don't require a running Stove instance.
 */
class WireMockExposedConfigurationTest :
  FunSpec({
    isolationMode = IsolationMode.InstancePerTest

    test("WireMockExposedConfiguration should have correct baseUrl format") {
      val config = WireMockExposedConfiguration(host = "localhost", port = 9090)
      config.baseUrl shouldBe "http://localhost:9090"
    }

    test("WireMockExposedConfiguration should handle different hosts") {
      val config = WireMockExposedConfiguration(host = "127.0.0.1", port = 8080)
      config.baseUrl shouldBe "http://127.0.0.1:8080"
    }

    test("WireMockExposedConfiguration should handle different ports") {
      val config = WireMockExposedConfiguration(host = "localhost", port = 0)
      config.baseUrl shouldBe "http://localhost:0"

      val config2 = WireMockExposedConfiguration(host = "localhost", port = 65535)
      config2.baseUrl shouldBe "http://localhost:65535"
    }

    test("WireMockSystemOptions default configureExposedConfiguration returns empty list") {
      val options = WireMockSystemOptions()
      val config = WireMockExposedConfiguration(host = "localhost", port = 9090)
      options.configureExposedConfiguration(config) shouldBe emptyList()
    }

    test("WireMockSystemOptions custom configureExposedConfiguration is called correctly") {
      val options = WireMockSystemOptions(
        port = 0,
        configureExposedConfiguration = { cfg ->
          listOf(
            "api.url=${cfg.baseUrl}",
            "api.port=${cfg.port}"
          )
        }
      )
      val config = WireMockExposedConfiguration(host = "localhost", port = 12345)
      val result = options.configureExposedConfiguration(config)

      result shouldBe listOf(
        "api.url=http://localhost:12345",
        "api.port=12345"
      )
    }
  })
