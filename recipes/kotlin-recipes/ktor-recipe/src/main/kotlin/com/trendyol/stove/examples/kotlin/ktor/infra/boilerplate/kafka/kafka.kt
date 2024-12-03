package com.trendyol.stove.examples.kotlin.ktor.infra.boilerplate.kafka

import com.mongodb.kotlin.client.coroutine.MongoClient
import com.trendyol.stove.examples.domain.ddd.EventPublisher
import com.trendyol.stove.examples.kotlin.ktor.application.KafkaConfiguration
import io.github.nomisRev.kafka.publisher.*
import io.github.nomisRev.kafka.receiver.*
import io.ktor.server.application.*
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.serialization.*
import org.koin.core.KoinApplication
import org.koin.dsl.*
import org.koin.ktor.ext.get
import java.util.*
import kotlin.time.Duration.Companion.seconds

fun KoinApplication.registerKafka(kafkaConfiguration: KafkaConfiguration) {
  modules(
    module {
      single { kafkaConfiguration }
      single { kafkaPublisher(get()) }
      single { kafkaReceiver(get()) }
      single { ConsumerEngine(getAll()) }
      single { KafkaDomainEventPublisher(get(), get()) }.bind<EventPublisher>()
      single { TopicResolver(get()) }
    }
  )
}

fun Application.configureConsumerEngine() {
  this.environment.monitor.subscribe(ApplicationStarted) {
    val consumerEngine = get<ConsumerEngine>()
    consumerEngine.start()
  }

  this.environment.monitor.subscribe(ApplicationStopPreparing) {
    val consumerEngine = get<ConsumerEngine>()
    consumerEngine.stop()

    get<MongoClient>().close()
  }
}

private fun kafkaPublisher(
  kafkaConfiguration: KafkaConfiguration
): KafkaPublisher<String, Any> = KafkaPublisher(
  PublisherSettings(
    bootstrapServers = kafkaConfiguration.bootstrapServers,
    valueSerializer = StoveKafkaValueSerializer(),
    keySerializer = StringSerializer(),
    properties = Properties().apply {
      putAll(
        mapOf(
          ProducerConfig.INTERCEPTOR_CLASSES_CONFIG to kafkaConfiguration.flattenInterceptorClasses()
        )
      )
    }
  )
)

private fun kafkaReceiver(
  kafkaConfiguration: KafkaConfiguration
): KafkaReceiver<String, Any> = KafkaReceiver(
  ReceiverSettings(
    bootstrapServers = kafkaConfiguration.bootstrapServers,
    keyDeserializer = StringDeserializer(),
    valueDeserializer = StoveKafkaValueDeserializer(),
    groupId = kafkaConfiguration.groupId,
    autoOffsetReset = kafkaConfiguration.autoOffsetReset(),
    commitStrategy = CommitStrategy.ByTime((kafkaConfiguration.heartbeatIntervalSeconds + 1).seconds),
    pollTimeout = 2.seconds,
    properties = Properties().apply {
      putAll(
        mapOf(
          ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG to kafkaConfiguration.autoCreateTopics.toString(),
          ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG to kafkaConfiguration.heartbeatIntervalSeconds.seconds.inWholeMilliseconds.toInt(),
          ConsumerConfig.INTERCEPTOR_CLASSES_CONFIG to kafkaConfiguration.flattenInterceptorClasses()
        )
      )
    }
  )
)

private fun KafkaConfiguration.autoOffsetReset(): AutoOffsetReset = when (autoOffsetReset) {
  "earliest" -> AutoOffsetReset.Earliest
  "latest" -> AutoOffsetReset.Latest
  else -> throw IllegalArgumentException("Unknown auto offset reset value: $autoOffsetReset")
}
