package com.trendyol.stove.testing.e2e.couchbase

import com.couchbase.client.kotlin.codec.*
import tools.jackson.databind.json.JsonMapper

class Jackson3JsonSerializer(
  private val mapper: JsonMapper
) : JsonSerializer {
  override fun <T> serialize(value: T, type: TypeRef<T>): ByteArray = mapper.writeValueAsBytes(value)

  override fun <T> deserialize(json: ByteArray, type: TypeRef<T>): T {
    val javaType = mapper.typeFactory.constructType(type.type)
    val result: T = mapper.readValue(json, javaType)
    if (result == null && !type.nullable) {
      throw NullPointerException("Can't deserialize null value into non-nullable type $type")
    }
    return result
  }
}
