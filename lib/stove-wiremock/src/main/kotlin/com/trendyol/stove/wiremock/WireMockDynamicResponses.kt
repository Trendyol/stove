package com.trendyol.stove.wiremock

import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder
import com.github.tomakehurst.wiremock.extension.ResponseDefinitionTransformerV2
import com.github.tomakehurst.wiremock.http.ResponseDefinition
import com.github.tomakehurst.wiremock.stubbing.ServeEvent
import com.github.tomakehurst.wiremock.verification.LoggedRequest
import java.util.concurrent.ConcurrentHashMap

/**
 * Serves lambda-computed responses for stubs registered through `mockDynamic`.
 * Responders are correlated to stubs via a metadata key so they survive
 * WireMock's own stub-id assignment.
 */
internal class WireMockDynamicResponses : ResponseDefinitionTransformerV2 {
  private val responders = ConcurrentHashMap<String, (LoggedRequest) -> ResponseDefinitionBuilder>()

  fun register(id: String, respond: (LoggedRequest) -> ResponseDefinitionBuilder) {
    responders[id] = respond
  }

  override fun getName(): String = NAME

  override fun applyGlobally(): Boolean = false

  override fun transform(serveEvent: ServeEvent): ResponseDefinition {
    val respond = serveEvent.stubMapping
      ?.metadata
      ?.takeIf { it.containsKey(METADATA_KEY) }
      ?.getString(METADATA_KEY)
      ?.let(responders::get)
      ?: return serveEvent.responseDefinition
    return respond(serveEvent.request).build()
  }

  companion object {
    const val NAME = "stove-dynamic-response"
    const val METADATA_KEY = "stoveDynamicResponseId"
  }
}
