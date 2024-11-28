package com.trendyol.stove.testing.e2e.kafka.stringserde

import com.trendyol.stove.testing.e2e.kafka.StoveBusinessException
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.serialization.Serdes
import org.slf4j.*
import org.springframework.boot.*
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.*
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.kafka.annotation.*
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory
import org.springframework.kafka.core.*
import org.springframework.kafka.listener.*
import org.springframework.util.backoff.FixedBackOff

@SpringBootApplication(scanBasePackages = ["com.trendyol.stove.testing.e2e.kafka.stringserde"])
@EnableKafka
@EnableConfigurationProperties(KafkaTestSpringBotApplicationForStringSerde.StringSerdeKafkaConf::class)
open class KafkaTestSpringBotApplicationForStringSerde {
  companion object {
    fun run(
      args: Array<String>,
      init: SpringApplication.() -> Unit = {}
    ): ConfigurableApplicationContext {
      System.setProperty("org.springframework.boot.logging.LoggingSystem", "none")
      return runApplication<KafkaTestSpringBotApplicationForStringSerde>(args = args) {
        webApplicationType = WebApplicationType.NONE
        init()
      }
    }
  }

  private val logger: Logger = LoggerFactory.getLogger(javaClass)

  @ConfigurationProperties(prefix = "kafka")
  @ConstructorBinding
  data class StringSerdeKafkaConf(
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
    config: StringSerdeKafkaConf
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
    config: StringSerdeKafkaConf
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
  fun listenFailed(message: String) {
    logger.info("Received Message in failed consumer: $message")
    throw StoveBusinessException("This exception is thrown intentionally for testing purposes.")
  }

  @KafkaListener(topics = ["topic-failed.DLT"], groupId = "group_id")
  fun listenDeadLetter(message: String) {
    logger.info("Received Message in the lead letter, and allowing the fail by just logging: $message")
  }
}
