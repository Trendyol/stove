package com.trendyol.stove.wiremock

import com.github.tomakehurst.wiremock.client.MappingBuilder
import com.github.tomakehurst.wiremock.client.WireMock.*
import com.github.tomakehurst.wiremock.matching.*
import com.trendyol.stove.serialization.StoveSerde

internal fun MappingBuilder.configureBodyContaining(
  requestContaining: Map<String, Any>,
  serde: StoveSerde<Any, ByteArray>
) {
  requestContaining.forEach { (key, value) ->
    val matcher = createValueMatcher(value, serde)
    val jsonPath = WireMockJsonPath.field(key)
    withRequestBody(matchingJsonPath(jsonPath, matcher))
  }
}

internal fun RequestPatternBuilder.configureBodyContaining(
  requestContaining: Map<String, Any>,
  serde: StoveSerde<Any, ByteArray>
) {
  requestContaining.forEach { (key, value) ->
    val matcher = createValueMatcher(value, serde)
    val jsonPath = WireMockJsonPath.field(key)
    withRequestBody(matchingJsonPath(jsonPath, matcher))
  }
}

private fun createValueMatcher(
  value: Any,
  serde: StoveSerde<Any, ByteArray>
): StringValuePattern = when (value) {
  is String -> equalTo(value)
  is Number -> equalTo(value.toString())
  is Boolean -> equalTo(value.toString())
  is Map<*, *> -> equalToJson(serde.serialize(value).decodeToString(), true, true)
  is Collection<*> -> equalToJson(serde.serialize(value).decodeToString(), true, true)
  else -> equalToJson(serde.serialize(value).decodeToString(), true, true)
}
