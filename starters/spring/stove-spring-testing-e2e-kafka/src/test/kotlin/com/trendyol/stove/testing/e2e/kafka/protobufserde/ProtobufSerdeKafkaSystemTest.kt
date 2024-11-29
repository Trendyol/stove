package com.trendyol.stove.testing.e2e.kafka.protobufserde

import com.google.protobuf.*
import com.trendyol.stove.spring.testing.e2e.kafka.v1.Example.Product
import com.trendyol.stove.spring.testing.e2e.kafka.v1.product
import com.trendyol.stove.testing.e2e.kafka.*
import com.trendyol.stove.testing.e2e.serialization.StoveSerde
import com.trendyol.stove.testing.e2e.springBoot
import com.trendyol.stove.testing.e2e.system.TestSystem
import com.trendyol.stove.testing.e2e.system.TestSystem.Companion.validate
import io.confluent.kafka.streams.serdes.protobuf.KafkaProtobufSerde
import io.kotest.core.spec.style.ShouldSpec
import kotlinx.serialization.ExperimentalSerializationApi
import org.springframework.context.support.beans
import kotlin.random.Random

@Suppress("UNCHECKED_CAST")
@OptIn(ExperimentalSerializationApi::class)
class StoveProtobufSerde : StoveSerde<Any, ByteArray> {
  private val protobufSerde: KafkaProtobufSerde<Message> = KafkaRegistry.createSerde()

  override fun serialize(value: Any): ByteArray = protobufSerde.serializer().serialize("any", value as Message)

  override fun <T : Any> deserialize(value: ByteArray, clazz: Class<T>): T {
    val ret: Message = protobufSerde.deserializer().deserialize("any", value)
    ret as DynamicMessage
    val parseFromMethod = clazz.getDeclaredMethod("parseFrom", ByteArray::class.java)
    val obj = parseFromMethod(ret, ret.toByteArray()) as T
    return obj
  }
}

class ProtobufSerdeKafkaSystemTest : ShouldSpec({
  beforeSpec {
    TestSystem()
      .with {
        kafka {
          KafkaSystemOptions(
            serdeForPublish = StoveProtobufSerde(),
            configureExposedConfiguration = {
              listOf(
                "kafka.bootstrapServers=${it.bootstrapServers}",
                "kafka.groupId=test-group",
                "kafka.offset=earliest",
                "kafka.schemaRegistryUrl=mock://mock-registry"
              )
            },
            containerOptions = KafkaContainerOptions {
            }
          )
        }
        springBoot(
          runner = { params ->
            KafkaTestSpringBotApplicationForProtobufSerde.run(params) {
              addInitializers(
                beans {
                  bean<TestSystemKafkaInterceptor<*, *>>()
                  bean { StoveProtobufSerde() }
                }
              )
            }
          },
          withParameters = listOf(
            "spring.lifecycle.timeout-per-shutdown-phase=0s"
          )
        )
      }.run()
  }

  afterSpec {
    TestSystem.stop()
  }

  should("publish and consume") {
    validate {
      kafka {
        val userId = Random.nextInt().toString()
        val productId = Random.nextInt().toString()
        val message = product {
          id = productId
          name = "product-${Random.nextInt()}"
          price = Random.nextDouble()
          currency = "eur"
          description = "description-${Random.nextInt()}"
        }
        val headers = mapOf("x-user-id" to userId)
        publish("topic-protobuf", message, headers = headers)
        shouldBePublished<Product> {
          actual == message && this.metadata.headers["x-user-id"] == userId && this.metadata.topic == "topic-protobuf"
        }
        shouldBeConsumed<Product> {
          actual == message && this.metadata.headers["x-user-id"] == userId && this.metadata.topic == "topic-protobuf"
        }
      }
    }
  }
})
