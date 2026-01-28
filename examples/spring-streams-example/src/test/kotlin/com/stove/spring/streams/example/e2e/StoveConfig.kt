package com.stove.spring.streams.example.e2e

import com.stove.spring.streams.example.e2e.ExampleTest.Companion.INPUT_TOPIC
import com.stove.spring.streams.example.e2e.ExampleTest.Companion.INPUT_TOPIC2
import com.stove.spring.streams.example.e2e.ExampleTest.Companion.OUTPUT_TOPIC
import com.trendyol.stove.*
import com.trendyol.stove.extensions.kotest.StoveKotestExtension
import com.trendyol.stove.kafka.*
import com.trendyol.stove.spring.bridge
import com.trendyol.stove.spring.springBoot
import com.trendyol.stove.system.*
import com.trendyol.stove.system.Stove
import com.trendyol.stove.system.stove
import com.trendyol.stove.tracing.tracing
import io.kotest.core.config.AbstractProjectConfig
import io.kotest.core.extensions.Extension
import org.apache.kafka.clients.admin.NewTopic
import stove.spring.streams.example.run

class StoveConfig : AbstractProjectConfig() {
  override val extensions: List<Extension> = listOf(StoveKotestExtension())

  @Suppress("LongMethod")
  override suspend fun beforeProject(): Unit = Stove()
    .also {
      stoveKafkaBridgePortDefault = PortFinder.findAvailablePortAsString()
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
      tracing {
        serviceName("spring-streams-example")
        enableSpanReceiver(port = 4318)
      }
      springBoot(
        runner = { parameters ->
          run(parameters)
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
      stove {
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

  override suspend fun afterProject(): Unit = Stove.stop()
}
