package com.trendyol.stove.testing.e2e.kafka.protobufserde

import com.google.protobuf.Message
import com.trendyol.stove.testing.e2e.kafka.StoveBusinessException
import io.confluent.kafka.schemaregistry.testutil.MockSchemaRegistry
import io.confluent.kafka.serializers.AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG
import io.confluent.kafka.streams.serdes.protobuf.KafkaProtobufSerde
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.serialization.*
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

sealed class KafkaRegistry(
  open val url: String
) {
  object Mock : KafkaRegistry("mock://mock-registry")

  data class Defined(
    override val url: String
  ) : KafkaRegistry(url)

  companion object {
    fun createSerde(registry: KafkaRegistry = Mock): KafkaProtobufSerde<Message> {
      val schemaRegistryClient = when (registry) {
        is Mock -> MockSchemaRegistry.getClientForScope("mock-registry")
        is Defined -> MockSchemaRegistry.getClientForScope(registry.url)
      }
      val serde: KafkaProtobufSerde<Message> = KafkaProtobufSerde<Message>(schemaRegistryClient)
      val serdeConfig: MutableMap<String, Any?> = HashMap()
      serdeConfig[SCHEMA_REGISTRY_URL_CONFIG] = registry.url
      serde.configure(serdeConfig, false)
      return serde
    }
  }
}

class ProtobufValueSerializer<T : Any> : Serializer<T> {
  private val protobufSerde: KafkaProtobufSerde<Message> = KafkaRegistry.createSerde()

  override fun serialize(
    topic: String,
    data: T
  ): ByteArray = when (data) {
    is ByteArray -> data
    else -> protobufSerde.serializer().serialize(topic, data as Message)
  }
}

class ProtobufValueDeserializer : Deserializer<Message> {
  private val protobufSerde: KafkaProtobufSerde<Message> = KafkaRegistry.createSerde()

  override fun deserialize(
    topic: String,
    data: ByteArray
  ): Message = protobufSerde.deserializer().deserialize(topic, data)
}

@SpringBootApplication(scanBasePackages = ["com.trendyol.stove.testing.e2e.kafka.protobufserde"])
@EnableKafka
@EnableConfigurationProperties(KafkaTestSpringBotApplicationForProtobufSerde.ProtobufSerdeKafkaConf::class)
open class KafkaTestSpringBotApplicationForProtobufSerde {
  companion object {
    fun run(
      args: Array<String>,
      init: SpringApplication.() -> Unit = {}
    ): ConfigurableApplicationContext {
      System.setProperty("org.springframework.boot.logging.LoggingSystem", "none")
      return runApplication<KafkaTestSpringBotApplicationForProtobufSerde>(args = args) {
        webApplicationType = WebApplicationType.NONE
        init()
      }
    }
  }

  private val logger: Logger = LoggerFactory.getLogger(javaClass)

  @ConfigurationProperties(prefix = "kafka")
  @ConstructorBinding
  data class ProtobufSerdeKafkaConf(
    val bootstrapServers: String,
    val groupId: String,
    val offset: String,
    val schemaRegistryUrl: String
  )

  @Bean
  open fun createConfiguredSerdeForRecordValues(config: ProtobufSerdeKafkaConf): KafkaProtobufSerde<Message> {
    val registry = when {
      config.schemaRegistryUrl.contains("mock://") -> KafkaRegistry.Mock
      else -> KafkaRegistry.Defined(config.schemaRegistryUrl)
    }
    return KafkaRegistry.createSerde(registry)
  }

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
    config: ProtobufSerdeKafkaConf
  ): ConsumerFactory<String, String> = DefaultKafkaConsumerFactory(
    mapOf(
      ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to config.bootstrapServers,
      ConsumerConfig.GROUP_ID_CONFIG to config.groupId,
      ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to config.offset,
      ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG to Serdes.String().deserializer().javaClass,
      ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG to ProtobufValueDeserializer().javaClass,
      ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG to 2000,
      ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG to 6000,
      ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG to 6000
    )
  )

  @Bean
  open fun kafkaTemplate(
    config: ProtobufSerdeKafkaConf
  ): KafkaTemplate<String, String> = KafkaTemplate(
    DefaultKafkaProducerFactory(
      mapOf(
        ProducerConfig.BOOTSTRAP_SERVERS_CONFIG to config.bootstrapServers,
        ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG to Serdes.String().serializer().javaClass,
        ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG to ProtobufValueSerializer<Any>().javaClass,
        ProducerConfig.ACKS_CONFIG to "1"
      )
    )
  )

  @KafkaListener(topics = ["topic-protobuf"], groupId = "group_id")
  fun listen(message: Message) {
    logger.info("Received Message in consumer: $message")
  }
}
