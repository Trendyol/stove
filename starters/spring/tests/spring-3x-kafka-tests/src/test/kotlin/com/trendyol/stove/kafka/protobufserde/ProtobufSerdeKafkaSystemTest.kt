package com.trendyol.stove.kafka.protobufserde

import com.trendyol.stove.kafka.*
import com.trendyol.stove.spring.springBoot
import com.trendyol.stove.system.Stove
import com.trendyol.stove.system.stove
import org.springframework.context.support.beans

/**
 * Spring Boot 3.x Protobuf Serde Kafka tests.
 * Test cases are inherited from [ProtobufSerdeKafkaSystemTests] in fixtures.
 */
class Boot3xProtobufSerdeKafkaSystemTest : ProtobufSerdeKafkaSystemTests() {
  init {
    beforeSpec {
      Stove()
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
              containerOptions = KafkaContainerOptions(tag = "8.0.3")
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
      Stove.stop()
    }
  }
}
