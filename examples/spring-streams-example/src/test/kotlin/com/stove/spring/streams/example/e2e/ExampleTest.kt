package com.stove.spring.streams.example.e2e

import arrow.core.Option
import com.google.protobuf.Message
import com.trendyol.stove.testing.e2e.standalone.kafka.kafka
import com.trendyol.stove.testing.e2e.system.TestSystem
import io.kotest.core.spec.style.FunSpec
import org.apache.kafka.common.serialization.StringDeserializer
import stove.example.protobuf.*
import stove.example.protobuf.Input1Value.Input1
import stove.example.protobuf.Input2Value.Input2
import stove.example.protobuf.OutputValue.Output
import java.util.*
import kotlin.time.Duration.Companion.seconds

class ExampleTest :
  FunSpec({
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
          publish(INPUT_TOPIC, input1Message, Option("test"))
          publish(INPUT_TOPIC2, input2Message, Option("test"))

        /*---------------------------
         |  verify messages consumed
         ----------------------------*/

          //  Assert input1 message is consumed
          shouldBeConsumed<Input1> {
            actual == input1Message
          }

          //  Assert input2 message is consumed
          shouldBeConsumed<Input2> {
            actual == input2Message
          }

        /*---------------------------
         |  verify messages published
         ----------------------------*/

          // Assert joined message is correctly published
          shouldBePublished<Output>(atLeastIn = 20.seconds) {
            actual.bsn == bsn
          }

          // Assert joined message is correctly published
          // Similar to test above, but is able to run even if no messages are published
          consumer<String, Message>(
            "output",
            valueDeserializer = StoveKafkaValueDeserializer(),
            keyDeserializer = StringDeserializer()
          ) { record ->
            if (Output.parseFrom(record.value().toByteArray()) != outputMessage) throw AssertionError()
          }
        }
      }
    }
  }) {
  companion object {
    const val INPUT_TOPIC = "input1"
    const val INPUT_TOPIC2 = "input2"
    const val OUTPUT_TOPIC = "output"
  }
}
