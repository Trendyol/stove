package com.trendyol.stove.testing.e2e.kafka.stringserde

import com.trendyol.stove.testing.e2e.kafka.*
import com.trendyol.stove.testing.e2e.serialization.StoveSerde
import com.trendyol.stove.testing.e2e.springBoot
import com.trendyol.stove.testing.e2e.system.TestSystem
import org.springframework.context.support.beans

/**
 * Spring Boot 3.x String Serde Kafka tests.
 * Test cases are inherited from [StringSerdeKafkaSystemTests] in fixtures.
 */
class Boot3xStringSerdeKafkaSystemTests : StringSerdeKafkaSystemTests(dltTopicSuffix = "-dlt") {
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
                  "kafka.offset=earliest"
                )
              },
              containerOptions = KafkaContainerOptions(tag = "7.8.1")
            )
          }
          springBoot(
            runner = { params ->
              KafkaTestSpringBotApplicationForStringSerde.run(params) {
                addInitializers(
                  beans {
                    bean<TestSystemKafkaInterceptor<*, *>>()
                    bean { StoveSerde.jackson.anyByteArraySerde() }
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
