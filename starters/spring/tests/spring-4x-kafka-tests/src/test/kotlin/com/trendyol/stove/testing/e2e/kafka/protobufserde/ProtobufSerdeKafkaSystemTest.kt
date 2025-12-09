package com.trendyol.stove.testing.e2e.kafka.protobufserde

import com.trendyol.stove.testing.e2e.*
import com.trendyol.stove.testing.e2e.kafka.*
import com.trendyol.stove.testing.e2e.system.TestSystem

/**
 * Spring Boot 4.x Protobuf Serde Kafka tests.
 * Test cases are inherited from [ProtobufSerdeKafkaSystemTests] in fixtures.
 */
class Boot4xProtobufSerdeKafkaSystemTest : ProtobufSerdeKafkaSystemTests() {
  init {
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
              containerOptions = KafkaContainerOptions(tag = "7.8.1")
            )
          }
          springBoot(
            runner = { params ->
              KafkaTestSpringBotApplicationForProtobufSerde.run(params) {
                addInitializers(
                  stoveSpring4xRegistrar {
                    registerBean<TestSystemKafkaInterceptor<*, *>>(primary = true)
                    registerBean { StoveProtobufSerde() }
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
  }
}
