package com.trendyol.stove.wiremock

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.stubbing.StubMapping
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.util.UUID

class WireMockStubInstallerTest :
  FunSpec({
    test("builders and built mappings share ID assignment installation and recording") {
      val server = WireMockServer(0)
      val recorded = mutableListOf<StubMapping>()
      val installer = WireMockStubInstaller(server) { recorded.add(it) }
      val callerId = UUID.randomUUID()

      server.start()
      try {
        val installedBuilder = installer.install(
          get("/installer/builder")
            .withId(callerId)
            .willReturn(aResponse().withStatus(200))
        )
        val installedMapping = installer.install(
          post("/installer/mapping")
            .willReturn(aResponse().withStatus(201))
            .build()
        )

        installedBuilder.id shouldNotBe callerId
        installedMapping.id shouldNotBe null
        installedBuilder.id shouldNotBe installedMapping.id
        server.stubMappings.map { it.id }.toSet() shouldBe setOf(
          installedBuilder.id,
          installedMapping.id
        )
        recorded shouldContainExactly listOf(installedBuilder, installedMapping)
      } finally {
        server.stop()
      }
    }
  })
