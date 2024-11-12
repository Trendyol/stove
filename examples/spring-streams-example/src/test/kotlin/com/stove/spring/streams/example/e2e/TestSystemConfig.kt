package com.stove.spring.streams.example.e2e

import com.trendyol.stove.testing.e2e.bridge
import com.trendyol.stove.testing.e2e.springBoot
import com.trendyol.stove.testing.e2e.standalone.kafka.KafkaContainerOptions
import com.trendyol.stove.testing.e2e.standalone.kafka.KafkaSystemOptions
import com.trendyol.stove.testing.e2e.standalone.kafka.kafka
import com.trendyol.stove.testing.e2e.system.TestSystem
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.extension.BeforeAllCallback
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.extension.ExtensionContext.Store.CloseableResource

class Init :
  BeforeAllCallback,
  CloseableResource {
  override fun beforeAll(context: ExtensionContext) {
    if (!started) {
      started = true
      runBlocking {
        TestSystem()
          .with {
            kafka {
              KafkaSystemOptions(
                listenPublishedMessagesFromStove = false,
                valueSerializer = StoveKafkaValueSerializer(),
                containerOptions = KafkaContainerOptions(tag = "latest") { }
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
                stove.spring.streams.example.run(parameters)
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
      }
    }
  }

  override fun close() {
    TestSystem.stop()
  }

  companion object {
    private var started = false
  }
}
