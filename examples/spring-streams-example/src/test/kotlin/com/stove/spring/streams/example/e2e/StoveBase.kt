package com.stove.spring.streams.example.e2e

import com.google.protobuf.Message
import com.trendyol.stove.testing.e2e.standalone.kafka.kafka
import com.trendyol.stove.testing.e2e.system.TestSystem
import io.confluent.kafka.streams.serdes.protobuf.KafkaProtobufSerde
import kotlinx.coroutines.runBlocking
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.common.serialization.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach

class StoveKafkaValueSerializer<T : Any> : Serializer<T> {
  private val protobufSerde: KafkaProtobufSerde<Message> = TestHelper().createConfiguredSerdeForRecordValues()

  override fun serialize(
    topic: String,
    data: T
  ): ByteArray {
    return when (data) {
      is ByteArray -> data
      else -> protobufSerde.serializer().serialize(topic, data as Message)
    }
  }
}

class StoveKafkaValueDeserializer<T : Any> : Deserializer<ByteArray> {
  override fun deserialize(
    topic: String,
    data: ByteArray
  ): ByteArray = data
}

abstract class StoveBase {
  // Topics
  val inputTopic1: String = "input1"
  val inputTopic2: String = "input2"
  val outputTopic: String = "output"

  @BeforeEach
  fun setup() = runBlocking {
    TestSystem.validate {
      kafka {
        adminOperations {
          createTopics(
            listOf(
              NewTopic(inputTopic1, 1, 1),
              NewTopic(inputTopic2, 1, 1),
              NewTopic(outputTopic, 1, 1)
            )
          )
          Thread.sleep(1000)
        }
      }
    }
  }

  @AfterEach
  fun after() = runBlocking {
    Thread.sleep(5000)
  }
}
