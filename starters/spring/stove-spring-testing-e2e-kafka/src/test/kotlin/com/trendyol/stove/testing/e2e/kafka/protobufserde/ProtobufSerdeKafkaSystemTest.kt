package com.trendyol.stove.testing.e2e.kafka.protobufserde

import com.google.protobuf.Message
import com.trendyol.stove.spring.testing.e2e.kafka.v1.*
import com.trendyol.stove.spring.testing.e2e.kafka.v1.Example.*
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

class ProtobufSerdeKafkaSystemTest :
  ShouldSpec({
    beforeSpec {
      TestSystem()
        .with {
          kafka {
            KafkaSystemOptions(
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
          val product = product {
            id = productId
            name = "product-${Random.nextInt()}"
            price = Random.nextDouble()
            currency = "eur"
            description = "description-${Random.nextInt()}"
          }
          val headers = mapOf("x-user-id" to userId)
          publish("topic-protobuf", product, headers = headers)
          shouldBePublished<Product> {
            actual == product && this.metadata.headers["x-user-id"] == userId && this.metadata.topic == "topic-protobuf"
          }
          shouldBeConsumed<Product> {
            actual == product && this.metadata.headers["x-user-id"] == userId && this.metadata.topic == "topic-protobuf"
          }

          val orderId = Random.nextInt().toString()
          val order = order {
            id = orderId
            customerId = userId
            products += product
          }
          publish("topic-protobuf", order, headers = headers)
          shouldBePublished<Order> {
            actual == order && this.metadata.headers["x-user-id"] == userId && this.metadata.topic == "topic-protobuf"
          }

          shouldBeConsumed<Order> {
            actual == order && this.metadata.headers["x-user-id"] == userId && this.metadata.topic == "topic-protobuf"
          }
        }
      }
    }
  })
