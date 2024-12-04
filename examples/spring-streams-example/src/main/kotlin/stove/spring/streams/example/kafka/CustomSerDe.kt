package stove.spring.streams.example.kafka

import com.google.protobuf.Message
import io.confluent.kafka.schemaregistry.testutil.MockSchemaRegistry
import io.confluent.kafka.serializers.AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG
import io.confluent.kafka.streams.serdes.protobuf.KafkaProtobufSerde
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

@Component
class CustomSerDe {
  @Value("\${kafka.schema-registry-url}")
  val schemaRegistryUrl = ""

  fun createSerdeForValues(): KafkaProtobufSerde<Message> = KafkaRegistry.createSerde(schemaRegistryUrl)
}

sealed class KafkaRegistry(
  open val url: String
) {
  object Mock : KafkaRegistry("mock://mock-registry")

  data class Defined(
    override val url: String
  ) : KafkaRegistry(url)

  companion object {
    fun createSerde(fromUrl: String): KafkaProtobufSerde<Message> = createSerde(
      if (fromUrl.contains(Mock.url)) Mock else Defined(fromUrl)
    )

    fun createSerde(registry: KafkaRegistry = Mock): KafkaProtobufSerde<Message> {
      val schemaRegistryClient = when (registry) {
        is Mock -> MockSchemaRegistry.getClientForScope("mock-registry")
        is Defined -> MockSchemaRegistry.getClientForScope(registry.url)
      }
      val serde: KafkaProtobufSerde<Message> = KafkaProtobufSerde<Message>(schemaRegistryClient)
      val serdeConfig: MutableMap<String, Any?> = HashMap()
      serdeConfig[SCHEMA_REGISTRY_URL_CONFIG] = registry.url
      serde.configure(serdeConfig, false)
      return serde
    }
  }
}
