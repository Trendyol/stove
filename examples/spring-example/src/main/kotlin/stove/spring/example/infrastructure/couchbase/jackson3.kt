package stove.spring.example.infrastructure.couchbase

import com.couchbase.client.java.codec.JsonSerializer
import tools.jackson.databind.json.JsonMapper

class Jackson3JsonSerializer(
  private val mapper: JsonMapper
) : JsonSerializer {
  override fun serialize(input: Any): ByteArray = mapper.writeValueAsBytes(input)

  override fun <T : Any> deserialize(target: Class<T>, input: ByteArray): T {
    val javaType = mapper.typeFactory.constructType(target)
    val result: T = mapper.readValue(input, javaType)
    return result
  }
}
