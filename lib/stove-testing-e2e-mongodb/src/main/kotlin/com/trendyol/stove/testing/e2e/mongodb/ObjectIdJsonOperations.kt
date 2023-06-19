package com.trendyol.stove.testing.e2e.mongodb

import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.SerializerProvider
import com.fasterxml.jackson.databind.deser.std.StdDeserializer
import com.fasterxml.jackson.databind.module.SimpleModule
import com.fasterxml.jackson.databind.node.TextNode
import com.fasterxml.jackson.databind.ser.std.StdSerializer
import org.bson.types.ObjectId

class ObjectIdSerializer : StdSerializer<ObjectId>(ObjectId::class.java) {
    override fun serialize(
        value: ObjectId,
        gen: JsonGenerator,
        provider: SerializerProvider
    ): Unit = gen.writeString(value.toHexString())
}

class ObjectIdDeserializer : StdDeserializer<ObjectId>(ObjectId::class.java) {
    override fun deserialize(
        parser: JsonParser,
        context: DeserializationContext
    ): ObjectId = when (val node = context.parser.codec.readValue(parser, JsonNode::class.java)) {
        is TextNode -> node.textValue().removeSurrounding("\"")
        is JsonNode -> node["\$oid"].textValue().removeSurrounding("\"")
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
