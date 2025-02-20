package com.stove.spring.streams.example.e2e

import com.stove.spring.streams.example.e2e.ExampleTest.Companion.INPUT_TOPIC
import com.stove.spring.streams.example.e2e.ExampleTest.Companion.INPUT_TOPIC2
import com.stove.spring.streams.example.e2e.ExampleTest.Companion.OUTPUT_TOPIC
import com.trendyol.stove.testing.e2e.*
import com.trendyol.stove.testing.e2e.standalone.kafka.*
import com.trendyol.stove.testing.e2e.system.TestSystem
import com.trendyol.stove.testing.e2e.system.TestSystem.Companion.validate
import io.kotest.core.config.AbstractProjectConfig
import org.apache.kafka.clients.admin.NewTopic

class Stove : AbstractProjectConfig() {
  @Suppress("LongMethod")
  override suspend fun beforeProject(): Unit = TestSystem()
    .also {
      stoveKafkaBridgePortDefault = "50054"
    }.with {
      kafka {
        KafkaSystemOptions(
          listenPublishedMessagesFromStove = false,
          serde = StoveProtobufSerde(),
          valueSerializer = StoveKafkaValueSerializer(),
          containerOptions = KafkaContainerOptions(tag = "7.8.1")
        ) {
          listOf(
            "kafka.bootstrapServers=${it.bootstrapServers}",
            "kafka.isSecure=false",
            "kafka.interceptorClasses=${it.interceptorClass}",
            "spring.kafka.streams.bootstrap-servers=${it.bootstrapServers}",
            "spring.kafka.producer.bootstrap-servers=${it.bootstrapServers}",
            "spring.kafka.consumer.bootstrap-servers=${it.bootstrapServers}"
          )
        }
      }
      bridge()
      springBoot(
        runner = { parameters ->
          stove.spring.streams.example
            .run(parameters)
        },
        withParameters = listOf(
          "server.port=8001",
          "logging.level.root=info",
          "logging.level.org.springframework.web=info",
          "spring.profiles.active=default",
          "kafka.heartbeatInSeconds=2",
          "kafka.autoCreateTopics=true",
          "kafka.offset=earliest",
          "kafka.secureKafka=false",
          "kafka.topic.create-topics=true",
          "kafka.schema-registry-url=mock://mock-registry"
        )
      )
    }.run()
    .also {
      validate {
        kafka {
          adminOperations {
            createTopics(
              listOf(
                NewTopic(INPUT_TOPIC, 1, 1),
                NewTopic(INPUT_TOPIC2, 1, 1),
                NewTopic(OUTPUT_TOPIC, 1, 1)
              )
            )
          }
        }
      }
    }

  override suspend fun afterProject(): Unit = TestSystem.stop()
}
