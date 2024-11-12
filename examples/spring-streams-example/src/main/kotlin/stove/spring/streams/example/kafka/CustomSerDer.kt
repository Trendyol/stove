package stove.spring.streams.example.kafka

import com.google.protobuf.Message
import io.confluent.kafka.schemaregistry.testutil.MockSchemaRegistry
import io.confluent.kafka.serializers.AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG
import io.confluent.kafka.streams.serdes.protobuf.KafkaProtobufSerde
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component

@Component
class CustomSerDer(
  private val env: Environment
) {
  @Value("\${kafka.schema-registry-url}")
  val schemaRegistryUrl = ""

  fun createConfiguredSerdeForRecordValues(): KafkaProtobufSerde<Message> {
    var serde: KafkaProtobufSerde<Message> = KafkaProtobufSerde<Message>()
    if (schemaRegistryUrl.contains("mock://")) {
      serde = KafkaProtobufSerde<Message>(MockSchemaRegistry.getClientForScope("mock-registry"))
    }
    val serdeConfig: MutableMap<String, Any?> = HashMap()
    serdeConfig[SCHEMA_REGISTRY_URL_CONFIG] = schemaRegistryUrl
    serde.configure(serdeConfig, false)
    return serde
  }
}
