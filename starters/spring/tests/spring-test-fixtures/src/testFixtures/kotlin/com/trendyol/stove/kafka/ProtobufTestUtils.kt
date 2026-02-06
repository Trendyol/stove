package com.trendyol.stove.kafka

import com.google.protobuf.Message
import com.trendyol.stove.serialization.StoveSerde
import io.confluent.kafka.schemaregistry.protobuf.ProtobufSchemaProvider
import io.confluent.kafka.schemaregistry.testutil.MockSchemaRegistry
import io.confluent.kafka.serializers.AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG
import io.confluent.kafka.streams.serdes.protobuf.KafkaProtobufSerde

/**
 * Schema registry abstraction for protobuf tests.
 */
sealed class KafkaRegistry(
  open val url: String
) {
  object Mock : KafkaRegistry("mock://mock-registry")

  data class Defined(
    override val url: String
  ) : KafkaRegistry(url)

  companion object {
    fun createSerde(registry: KafkaRegistry = Mock): KafkaProtobufSerde<Message> {
      val schemaRegistryClient = when (registry) {
        is Mock -> MockSchemaRegistry.getClientForScope("mock-registry", listOf(ProtobufSchemaProvider()))
        is Defined -> MockSchemaRegistry.getClientForScope(registry.url, listOf(ProtobufSchemaProvider()))
      }
      val serde: KafkaProtobufSerde<Message> = KafkaProtobufSerde<Message>(schemaRegistryClient)
      val serdeConfig: MutableMap<String, Any?> = HashMap()
      serdeConfig[SCHEMA_REGISTRY_URL_CONFIG] = registry.url
      serde.configure(serdeConfig, false)
      return serde
    }
  }
}

/**
 * Shared protobuf serde for Stove Kafka tests.
 */
@Suppress("UNCHECKED_CAST")
class StoveProtobufSerde : StoveSerde<Any, ByteArray> {
  private val parseFromMethod = "parseFrom"
  private val protobufSerde: KafkaProtobufSerde<Message> = KafkaRegistry.createSerde()

  override fun serialize(value: Any): ByteArray = protobufSerde.serializer().serialize("any", value as Message)

  override fun <T : Any> deserialize(value: ByteArray, clazz: Class<T>): T {
    val incoming: Message = protobufSerde.deserializer().deserialize("any", value)
    incoming.isAssignableFrom(clazz).also { isAssignableFrom ->
      require(isAssignableFrom) {
        "Expected '${clazz.simpleName}' but got '${incoming.descriptorForType.name}'. " +
          "This could be transient ser/de problem since the message stream is constantly checked if the expected message is arrived, " +
          "so you can ignore this error if you are sure that the message is the expected one."
      }
    }

    val parseFromMethod = clazz.getDeclaredMethod(parseFromMethod, ByteArray::class.java)
    val parsed = parseFromMethod(incoming, incoming.toByteArray()) as T
    return parsed
  }
}

private fun Message.isAssignableFrom(clazz: Class<*>): Boolean = this.descriptorForType.name == clazz.simpleName
