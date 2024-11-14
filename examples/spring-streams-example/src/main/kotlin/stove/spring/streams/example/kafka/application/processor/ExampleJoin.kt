package stove.spring.streams.example.kafka.application.processor

import com.google.protobuf.Message
import io.confluent.kafka.streams.serdes.protobuf.KafkaProtobufSerde
import org.apache.kafka.common.serialization.*
import org.apache.kafka.streams.StreamsBuilder
import org.apache.kafka.streams.kstream.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.kafka.annotation.*
import org.springframework.stereotype.Component
import stove.example.protobuf.Input1Value.Input1
import stove.example.protobuf.Input2Value.Input2
import stove.example.protobuf.output
import stove.spring.streams.example.kafka.CustomSerDe

@Component
@EnableKafka
@EnableKafkaStreams
class ExampleJoin(customSerDe: CustomSerDe) {
  private val protobufSerde: KafkaProtobufSerde<Message> = customSerDe.createConfiguredSerdeForRecordValues()
  private val byteArraySerde: Serde<ByteArray> = Serdes.ByteArray()
  private val stringSerde: Serde<String> = Serdes.String()

  @Autowired
  fun buildPipeline(streamsBuilder: StreamsBuilder) {
    val input1: KTable<String, Message> = streamsBuilder
      .stream("input1", Consumed.with(stringSerde, protobufSerde))
      .toTable(Materialized.with(stringSerde, protobufSerde))

    val input2: KTable<String, Message> = streamsBuilder
      .stream("input2", Consumed.with(stringSerde, protobufSerde))
      .toTable(Materialized.with(stringSerde, protobufSerde))

    val joinedTable =
      input1.join(
        input2,
        { input1Message: Message, input2Message: Message ->
          protobufSerde.serializer().serialize(
            "output",
            output {
              this.firstName = Input1.parseFrom(input1Message.toByteArray()).firstName
              this.lastName = Input1.parseFrom(input1Message.toByteArray()).lastName
              this.bsn = Input2.parseFrom(input2Message.toByteArray()).bsn
              this.age = Input2.parseFrom(input2Message.toByteArray()).age
            }
          )
        }
      )
    joinedTable.toStream().to("output", Produced.with(stringSerde, byteArraySerde))
  }
}
