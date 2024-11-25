package com.trendyol.stove.testing.e2e.kafka

import com.trendyol.stove.testing.e2e.serialization.StoveSerde
import com.trendyol.stove.testing.e2e.springBoot
import com.trendyol.stove.testing.e2e.system.TestSystem
import com.trendyol.stove.testing.e2e.system.TestSystem.Companion.validate
import io.kotest.core.config.AbstractProjectConfig
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.serialization.Serdes
import org.slf4j.Logger
import org.springframework.boot.*
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.*
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.support.beans
import org.springframework.kafka.annotation.*
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory
import org.springframework.kafka.core.*
import org.springframework.kafka.listener.*
import org.springframework.util.backoff.FixedBackOff
import kotlin.random.Random

object KafkaSystemTestAppRunner {
  fun run(
    args: Array<String>,
    init: SpringApplication.() -> Unit = {}
  ): ConfigurableApplicationContext {
    System.setProperty("org.springframework.boot.logging.LoggingSystem", "none")
    return runApplication<KafkaTestSpringBotApplication>(args = args) {
      init()
    }
  }
}

@SpringBootApplication
@EnableKafka
@EnableConfigurationProperties(KafkaTestSpringBotApplication.KafkaTestSpringBotApplicationConfiguration::class)
open class KafkaTestSpringBotApplication {
  private val logger: Logger = org.slf4j.LoggerFactory.getLogger(javaClass)

  @ConfigurationProperties(prefix = "kafka")
  @ConstructorBinding
  data class KafkaTestSpringBotApplicationConfiguration(
    val bootstrapServers: String,
    val groupId: String,
    val offset: String
  )

  @Bean
  open fun kafkaListenerContainerFactory(
    consumerFactory: ConsumerFactory<String, String>,
    interceptor: RecordInterceptor<String, String>,
    recoverer: DeadLetterPublishingRecoverer
  ): ConcurrentKafkaListenerContainerFactory<String, String> {
    val factory = ConcurrentKafkaListenerContainerFactory<String, String>()
    factory.consumerFactory = consumerFactory
    factory.setCommonErrorHandler(
      DefaultErrorHandler(
        recoverer,
        FixedBackOff(20, 1)
      ).also { it.addNotRetryableExceptions(StoveBusinessException::class.java) }
    )
    factory.setRecordInterceptor(interceptor)
    return factory
  }

  @Bean
  open fun recoverer(
    kafkaTemplate: KafkaTemplate<*, *>
  ): DeadLetterPublishingRecoverer = DeadLetterPublishingRecoverer(kafkaTemplate)

  @Bean
  open fun consumerFactory(
    config: KafkaTestSpringBotApplicationConfiguration
  ): ConsumerFactory<String, String> = DefaultKafkaConsumerFactory(
    mapOf(
      ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to config.bootstrapServers,
      ConsumerConfig.GROUP_ID_CONFIG to config.groupId,
      ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to config.offset,
      ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG to Serdes.String().deserializer().javaClass,
      ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG to Serdes.String().deserializer().javaClass,
      ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG to 2000,
      ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG to 6000,
      ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG to 6000
    )
  )

  @Bean
  open fun kafkaTemplate(
    config: KafkaTestSpringBotApplicationConfiguration
  ): KafkaTemplate<String, String> = KafkaTemplate(
    DefaultKafkaProducerFactory(
      mapOf(
        ProducerConfig.BOOTSTRAP_SERVERS_CONFIG to config.bootstrapServers,
        ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG to Serdes.String().serializer().javaClass,
        ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG to Serdes.String().serializer().javaClass,
        ProducerConfig.ACKS_CONFIG to "1"
      )
    )
  )

  @KafkaListener(topics = ["topic"], groupId = "group_id")
  fun listen(message: String) {
    logger.info("Received Message in consumer: $message")
  }

  @KafkaListener(topics = ["topic-failed"], groupId = "group_id")
  fun listen_failed(message: String) {
    logger.info("Received Message in failed consumer: $message")
    throw StoveBusinessException("This exception is thrown intentionally for testing purposes.")
  }

  @KafkaListener(topics = ["topic-failed.DLT"], groupId = "group_id")
  fun listen_dead_letter(message: String) {
    logger.info("Received Message in the lead letter, and allowing the fail by just logging: $message")
  }
}

class StoveBusinessException(message: String) : Exception(message)

class Setup : AbstractProjectConfig() {
  override suspend fun beforeProject(): Unit =
    TestSystem()
      .with {
        kafka {
          KafkaSystemOptions(
            serde = StoveSerde.jackson.anyJsonStringSerde(),
            configureExposedConfiguration = {
              listOf(
                "kafka.bootstrapServers=${it.bootstrapServers}",
                "kafka.groupId=test-group",
                "kafka.offset=earliest"
              )
            },
            containerOptions = KafkaContainerOptions {
            }
          )
        }
        springBoot(
          runner = { params ->
            KafkaSystemTestAppRunner.run(params) {
              addInitializers(
                beans {
                  bean<TestSystemKafkaInterceptor<*, *>>()
                  bean { StoveSerde.jackson.anyByteArraySerde() }
                }
              )
            }
          }
        )
      }.run()

  override suspend fun afterProject(): Unit = TestSystem.stop()
}

class KafkaSystemTests : ShouldSpec({
  should("publish and consume") {
    validate {
      kafka {
        val userId = Random.nextInt().toString()
        val message = "this message is coming from ${testCase.descriptor.id.value} and testName is ${testCase.name.testName}"
        val headers = mapOf("x-user-id" to userId)
        publish("topic", message, headers = headers)
        shouldBePublished<Any> {
          actual == message && this.metadata.headers["x-user-id"] == userId && this.metadata.topic == "topic"
        }
        shouldBeConsumed<Any> {
          actual == message && this.metadata.headers["x-user-id"] == userId && this.metadata.topic == "topic"
        }
      }
    }
  }

  should("publish and consume with failed consumer") {
    shouldThrowMaybe<StoveBusinessException> {
      validate {
        kafka {
          val userId = Random.nextInt().toString()
          val message = "this message is coming from ${testCase.descriptor.id.value} and testName is ${testCase.name.testName}"
          val headers = mapOf("x-user-id" to userId)
          publish("topic-failed", message, headers = headers)
          shouldBePublished<Any> {
            actual == message && this.metadata.headers["x-user-id"] == userId && this.metadata.topic == "topic-failed"
          }
          shouldBeFailed<Any> {
            actual == message && this.metadata.headers["x-user-id"] == userId && this.metadata.topic == "topic-failed" && reason is StoveBusinessException
          }

          shouldBePublished<Any> {
            actual == message && this.metadata.headers["x-user-id"] == userId && this.metadata.topic == "topic-failed.DLT"
          }
        }
      }
    }
  }

  should("admin operations") {
    validate {
      kafka {
        adminOperations {
          val topic = "topic"
          createTopics(listOf(NewTopic(topic, 1, 1)))
          listTopics().names().get().contains(topic) shouldBe true
          deleteTopics(listOf(topic))
          listTopics().names().get().contains(topic) shouldBe false
        }
      }
    }
  }

  should("publish with ser/de") {
    validate {
      kafka {
        val userId = Random.nextInt().toString()
        val message = "this message is coming from ${testCase.descriptor.id.value} and testName is ${testCase.name.testName}"
        val headers = mapOf("x-user-id" to userId)
        publishWithSerde("topic", message, serde = StoveSerde.jackson.anyJsonStringSerde(), headers = headers)
        shouldBePublished<String> {
          actual == message && this.metadata.headers["x-user-id"] == userId && this.metadata.topic == "topic"
        }
        shouldBeConsumed<String> {
          actual == message && this.metadata.headers["x-user-id"] == userId && this.metadata.topic == "topic"
        }
      }
    }
  }
})
