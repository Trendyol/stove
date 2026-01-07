package com.trendyol.stove.kafka.stringserde

import com.trendyol.stove.kafka.*
import com.trendyol.stove.serialization.StoveSerde
import com.trendyol.stove.spring.springBoot
import com.trendyol.stove.system.Stove
import com.trendyol.stove.system.stove
import org.springframework.context.support.beans

/**
 * Spring Boot 3.x String Serde Kafka tests.
 * Test cases are inherited from [StringSerdeKafkaSystemTests] in fixtures.
 */
class Boot3xStringSerdeKafkaSystemTests : StringSerdeKafkaSystemTests(dltTopicSuffix = "-dlt") {
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
      Stove.stop()
    }
  }
}
