package com.trendyol.stove.testing.e2e.standalone.kafka.intercepting

import CommittedMessage
import ConsumedMessage
import PublishedMessage
import StoveKafkaObserverServiceClient
import com.fasterxml.jackson.databind.ObjectMapper
import com.squareup.wire.*
import com.trendyol.stove.functional.*
import com.trendyol.stove.testing.e2e.standalone.kafka.*
import kotlinx.coroutines.runBlocking
import okhttp3.*
import org.apache.kafka.clients.consumer.*
import org.apache.kafka.clients.producer.*
import org.apache.kafka.common.TopicPartition
import org.slf4j.Logger

@Suppress("UNUSED")
class StoveKafkaBridge<K, V> : ConsumerInterceptor<K, V>, ProducerInterceptor<K, V> {
  private val logger: Logger = org.slf4j.LoggerFactory.getLogger(StoveKafkaBridge::class.java)

  private val client: StoveKafkaObserverServiceClient by lazy { startGrpcClient() }
  private val mapper: ObjectMapper by lazy { stoveKafkaObjectMapperRef }

  override fun onSend(record: ProducerRecord<K, V>): ProducerRecord<K, V> = runBlocking {
    record.also { send(publishedMessage(it)) }
  }

  override fun onConsume(records: ConsumerRecords<K, V>): ConsumerRecords<K, V> = runBlocking {
    records.also { consumedMessages(it).forEach { message -> send(message) } }
  }

  override fun onCommit(offsets: MutableMap<TopicPartition, OffsetAndMetadata>) = runBlocking {
    committedMessages(offsets).forEach { send(it) }
  }

  override fun configure(configs: MutableMap<String, *>) = Unit

  override fun close() = Unit

  override fun onAcknowledgement(metadata: RecordMetadata, exception: Exception) = Unit

  private suspend fun send(consumedMessage: ConsumedMessage) {
    Try {
      client.onConsumedMessage().execute(consumedMessage)
    }.map {
      logger.info("Consumed message sent to Stove Kafka Bridge: $consumedMessage")
    }.recover { e ->
      logger.error("Failed to send consumed message to Stove Kafka Bridge: $consumedMessage", e)
    }
  }

  private suspend fun send(committedMessage: CommittedMessage) {
    Try {
      client.onCommittedMessage().execute(committedMessage)
    }.map {
      logger.info("Committed message sent to Stove Kafka Bridge: $committedMessage")
    }.recover { e ->
      logger.error("Failed to send committed message to Stove Kafka Bridge: $committedMessage", e)
    }
  }

  private suspend fun send(publishedMessage: PublishedMessage) {
    Try {
      client.onPublishedMessage().execute(publishedMessage)
    }.map {
      logger.info("Published message sent to Stove Kafka Bridge: $publishedMessage")
    }.recover { e ->
      logger.error("Failed to send published message to Stove Kafka Bridge: $publishedMessage", e)
    }
  }

  private fun consumedMessages(records: ConsumerRecords<K, V>) = records.map {
    ConsumedMessage(
      key = it.key().toString(),
      message = mapper.writeValueAsString(it.value()),
      topic = it.topic(),
      headers = it.headers().associate { it.key() to it.value().toString() }
    )
  }

  private fun publishedMessage(record: ProducerRecord<K, V>) = ConsumedMessage(
    key = record.key().toString(),
    message = mapper.writeValueAsString(record.value()),
    topic = record.topic(),
    headers = record.headers().associate { it.key() to it.value().toString() }
  )

  private fun committedMessages(
    offsets: MutableMap<TopicPartition, OffsetAndMetadata>
  ): List<CommittedMessage> = offsets.map {
    CommittedMessage(
      topic = it.key.topic(),
      partition = it.key.partition(),
      offset = it.value.offset()
    )
  }

  private fun startGrpcClient(): StoveKafkaObserverServiceClient {
    val onPort = System.getenv(STOVE_KAFKA_BRIDGE_PORT) ?: STOVE_KAFKA_BRIDGE_PORT_DEFAULT
    logger.info("Connecting to Stove Kafka Bridge on port $onPort")
    return Try { createClient(onPort) }
      .map {
        logger.info("Stove Kafka Observer Client created on port $onPort")
        it
      }.getOrElse { error("failed to connect Stove Kafka observer client") }
  }

  private fun createClient(onPort: String) = GrpcClient.Builder()
    .client(OkHttpClient.Builder().protocols(listOf(Protocol.H2_PRIOR_KNOWLEDGE)).build())
    .baseUrl("http://localhost:$onPort".toHttpUrl())
    .build()
    .create<StoveKafkaObserverServiceClient>()
}
