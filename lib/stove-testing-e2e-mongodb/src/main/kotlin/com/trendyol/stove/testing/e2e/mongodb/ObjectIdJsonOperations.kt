package com.trendyol.stove.testing.e2e.mongodb

import org.bson.types.ObjectId
import tools.jackson.core.*
import tools.jackson.databind.*
import tools.jackson.databind.deser.std.StdDeserializer
import tools.jackson.databind.module.SimpleModule
import tools.jackson.databind.ser.std.StdSerializer

class ObjectIdSerializer : StdSerializer<ObjectId>(ObjectId::class.java) {
  override fun serialize(
    value: ObjectId,
    gen: JsonGenerator,
    provider: SerializationContext
  ) {
    gen.writeString(value.toHexString())
  }
}

class ObjectIdDeserializer : StdDeserializer<ObjectId>(ObjectId::class.java) {
  override fun deserialize(
    parser: JsonParser,
    context: DeserializationContext
  ): ObjectId = when (val node = context.parser.readValueAs(JsonNode::class.java)) {
    is JsonNode -> node[$$"$oid"].asString().removeSurrounding("\"")

    else -> throw IllegalArgumentException(
      "ObjectId (\$oid) could not be deserialized, this is because JsonNode is not properly recognized."
    )
  }.let { ObjectId(it) }
}

class ObjectIdModule : SimpleModule() {
  init {
    addSerializer(ObjectId::class.java, ObjectIdSerializer())
    addDeserializer(ObjectId::class.java, ObjectIdDeserializer())
  }
}
