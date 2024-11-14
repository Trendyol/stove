package com.stove.spring.streams.example.e2e

import arrow.core.Option
import com.google.protobuf.Message
import com.trendyol.stove.testing.e2e.standalone.kafka.kafka
import com.trendyol.stove.testing.e2e.system.TestSystem
import io.confluent.kafka.streams.serdes.protobuf.KafkaProtobufSerde
import io.kotest.core.spec.style.FunSpec
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.common.serialization.StringDeserializer
import stove.example.protobuf.Input1Value.Input1
import stove.example.protobuf.Input2Value.Input2
import stove.example.protobuf.OutputValue.Output
import stove.example.protobuf.input1
import stove.example.protobuf.input2
import stove.example.protobuf.output
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

class ExampleTest : FunSpec ({
  val helper = TestHelper()

  val protobufSerde: KafkaProtobufSerde<Message> = helper.createConfiguredSerdeForRecordValues()

  // Topics
  val inputTopic1 = "input1"
  val inputTopic2 = "input2"
  val outputTopic = "output"

  beforeSpec {
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

  afterEach { Thread.sleep(5000) }

  test("expect join") {
      /*-------------------------
        |  Create test data
        --------------------------*/
    val firstName = UUID.randomUUID().toString()
    val lastName = UUID.randomUUID().toString()
    val bsn = UUID.randomUUID().toString()
    val age = 18

    // create input
    val input1Message = input1 {
      this.firstName = firstName
      this.lastName = lastName
    }
    val input2Message = input2 {
      this.bsn = bsn
      this.age = age
    }
    val outputMessage = output {
      this.firstName = firstName
      this.lastName = lastName
      this.bsn = bsn
      this.age = age
    }

    TestSystem.validate {
      kafka {
        /*-------------------------
         |  publish kafka messages
         --------------------------*/

        // inputs
        publish(inputTopic1, input1Message, Option("test"))
        publish(inputTopic2, input2Message, Option("test"))

        // Give time to process
        Thread.sleep(10000)

        /*---------------------------
         |  verify messages consumed
         ----------------------------*/

        //  Assert input1 message is consumed
        shouldBeConsumed<Any> {
          val message = helper.getMessageFromBase64(actual, protobufSerde)
          if (message == null) false else helper.assertMessage(message, Input1.getDescriptor().name) {
            Input1.parseFrom(message.toByteArray()) == input1Message
          }
        }

        //  Assert input2 message is consumed
        shouldBeConsumed<Any> {
          val message = helper.getMessageFromBase64(actual, protobufSerde)
          if (message == null) false else helper.assertMessage(message, Input2.getDescriptor().name) {
            Input2.parseFrom(message.toByteArray()) == input2Message
          }
        }

        /*---------------------------
         |  verify messages published
         ----------------------------*/

        // Assert joined message is correctly published
        shouldBePublished<Any> (atLeastIn = 20.seconds) {
          val message = helper.getMessageFromBase64(actual, protobufSerde)
          if (message == null) false else helper.assertMessage(message, Output.getDescriptor().name) {
            Output.parseFrom(message.toByteArray()) == outputMessage
          }
        }

        // Assert joined message is correctly published
        // Similar to test above, but is able to run even if no messages are published
        consumer<String, Message>(
          "output",
          valueDeserializer = StoveKafkaValueDeserializer<ByteArray>(),
          keyDeserializer = StringDeserializer(),
        ) { record ->
          if (Output.parseFrom(record.value().toByteArray()) != outputMessage) throw AssertionError()
        }
      }
    }
  }
})
