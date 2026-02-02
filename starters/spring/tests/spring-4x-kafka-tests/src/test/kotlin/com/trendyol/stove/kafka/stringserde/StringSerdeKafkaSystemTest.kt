package com.trendyol.stove.kafka.stringserde

import com.trendyol.stove.kafka.*
import com.trendyol.stove.serialization.StoveSerde
import com.trendyol.stove.spring.springBoot
import com.trendyol.stove.spring.stoveSpring4xRegistrar
import com.trendyol.stove.system.Stove
import com.trendyol.stove.system.stove

/**
 * Spring Boot 4.x String Serde Kafka tests.
 * Test cases are inherited from [StringSerdeKafkaSystemTests] in fixtures.
 * Uses [com.trendyol.stove.spring.stoveSpring4xRegistrar] with `registerBean<T>()` DSL.
 */
class Boot4xStringSerdeKafkaSystemTests : StringSerdeKafkaSystemTests(dltTopicSuffix = "-dlt") {
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
              containerOptions = KafkaContainerOptions(tag = "8.0.3")
            )
          }
          springBoot(
            runner = { params ->
              KafkaTestSpringBotApplicationForStringSerde.run(params) {
                addInitializers(
                  stoveSpring4xRegistrar {
                    registerBean<TestSystemKafkaInterceptor<*, *>>(primary = true)
                    registerBean { StoveSerde.jackson.anyByteArraySerde() }
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
