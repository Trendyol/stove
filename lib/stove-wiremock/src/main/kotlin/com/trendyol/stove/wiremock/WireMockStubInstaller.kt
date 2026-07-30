package com.trendyol.stove.wiremock

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.MappingBuilder
import com.github.tomakehurst.wiremock.stubbing.StubMapping
import java.util.UUID

/**
 * The shared final installation step for stable, structured, and native Stove stubs.
 *
 * API-specific matching, response, naming, metadata, and reporting policies are applied
 * before reaching this class.
 */
internal class WireMockStubInstaller(
  private val server: WireMockServer,
  private val onInstalled: (StubMapping) -> Unit
) {
  fun install(builder: MappingBuilder): StubMapping =
    server
      .stubFor(builder.withId(UUID.randomUUID()))
      .also(onInstalled)

  fun install(mapping: StubMapping): StubMapping {
    mapping.id = UUID.randomUUID()
    server.addStubMapping(mapping)
    onInstalled(mapping)
    return mapping
  }

  fun installAll(mappings: Iterable<StubMapping>): List<StubMapping> =
    mappings.map(::install)
}
