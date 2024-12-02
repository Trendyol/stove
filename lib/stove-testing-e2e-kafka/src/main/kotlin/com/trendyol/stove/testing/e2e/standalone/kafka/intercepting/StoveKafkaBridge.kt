package com.trendyol.stove.testing.e2e.standalone.kafka.intercepting

import com.squareup.wire.GrpcException
import com.trendyol.stove.functional.*
import com.trendyol.stove.testing.e2e.serialization.StoveSerde
import com.trendyol.stove.testing.e2e.standalone.kafka.*
import kotlinx.coroutines.runBlocking
import okio.ByteString.Companion.toByteString
import org.apache.kafka.clients.consumer.*
import org.apache.kafka.clients.producer.*
import org.apache.kafka.common.TopicPartition
import org.slf4j.Logger
import java.nio.charset.Charset
import java.util.*

@Suppress("UNUSED")
class StoveKafkaBridge<K, V> : ConsumerInterceptor<K, V>, ProducerInterceptor<K, V> {
  private val logger: Logger = org.slf4j.LoggerFactory.getLogger(StoveKafkaBridge::class.java)
  private val client: StoveKafkaObserverServiceClient by lazy { startGrpcClient() }
  private val serde: StoveSerde<Any, ByteArray> by lazy { stoveSerdeRef }

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

  override fun onAcknowledgement(metadata: RecordMetadata?, exception: Exception?) = runBlocking {
    ackedMessages(metadata, exception).forEach { send(it) }
  }

  private suspend fun send(consumedMessage: ConsumedMessage) {
    Try {
      client.onConsumedMessage().execute(consumedMessage)
    }.map {
      logger.info("Consumed message sent to Stove Kafka Bridge: $consumedMessage")
    }.recover { e ->
      when {
        e is GrpcException && e.grpcStatus.code == 2 && e.grpcStatus.name == "UNKNOWN" -> Unit
        else -> logger.error("Failed to send consumed message to Stove Kafka Bridge: $consumedMessage", e)
      }
    }
  }

  private suspend fun send(committedMessage: CommittedMessage) {
    Try {
      client.onCommittedMessage().execute(committedMessage)
    }.map {
      logger.info("Committed message sent to Stove Kafka Bridge: $committedMessage")
    }.recover { e ->
      when {
        e is GrpcException && e.grpcStatus.code == 2 && e.grpcStatus.name == "UNKNOWN" -> Unit
        else -> logger.error("Failed to send committed message to Stove Kafka Bridge: $committedMessage", e)
      }
    }
  }

  private suspend fun send(publishedMessage: PublishedMessage) {
    Try {
      client.onPublishedMessage().execute(publishedMessage)
    }.map {
      logger.info("Published message sent to Stove Kafka Bridge: $publishedMessage")
    }.recover { e ->
      when {
        e is GrpcException && e.grpcStatus.code == 2 && e.grpcStatus.name == "UNKNOWN" -> Unit
        else -> logger.error("Failed to send published message to Stove Kafka Bridge: $publishedMessage", e)
      }
    }
  }

  private suspend fun send(ackedMessage: AcknowledgedMessage) {
    Try {
      client.onAcknowledgedMessage().execute(ackedMessage)
    }.map {
      logger.info("Acknowledged message sent to Stove Kafka Bridge: $ackedMessage")
    }.recover { e ->
      when {
        e is GrpcException && e.grpcStatus.code == 2 && e.grpcStatus.name == "UNKNOWN" -> Unit
        else -> logger.error("Failed to send acknowledged message to Stove Kafka Bridge: $ackedMessage", e)
      }
    }
  }

  private fun ackedMessages(metadata: RecordMetadata?, exception: Exception?): List<AcknowledgedMessage> {
    val ackedMessage = AcknowledgedMessage(
      id = UUID.randomUUID().toString(),
      topic = metadata?.topic() ?: "",
      partition = metadata?.partition() ?: -1,
      offset = metadata?.offset() ?: -1,
      exception = exception?.message ?: ""
    )
    return listOf(ackedMessage)
  }

  private fun consumedMessages(records: ConsumerRecords<K, V>) = records.map { record ->
    ConsumedMessage(
      id = UUID.randomUUID().toString(),
      key = record.key().toString(),
      message = serializeIfNotYet(record.value()).toByteString(),
      topic = record.topic(),
      offset = record.offset(),
      partition = record.partition(),
      headers = record.headers().associate { it.key() to it.value().toString(Charset.defaultCharset()) }
    )
  }

  private fun publishedMessage(record: ProducerRecord<K, V>) = PublishedMessage(
    id = UUID.randomUUID().toString(),
    key = record.key().toString(),
    message = serializeIfNotYet(record.value()).toByteString(),
    topic = record.topic(),
    headers = record.headers().associate { it.key() to it.value().toString(Charset.defaultCharset()) }
  )

  private fun committedMessages(
    offsets: Map<TopicPartition, OffsetAndMetadata>
  ): List<CommittedMessage> = offsets.map {
    CommittedMessage(
      id = UUID.randomUUID().toString(),
      topic = it.key.topic(),
      partition = it.key.partition(),
      offset = it.value.offset(),
      metadata = it.value.metadata()
    )
  }

  private fun serializeIfNotYet(value: V): ByteArray = when (value) {
    is ByteArray -> value
    else -> serde.serialize(value as Any)
  }

  private fun startGrpcClient(): StoveKafkaObserverServiceClient {
    val onPort = System.getenv(STOVE_KAFKA_BRIDGE_PORT) ?: stoveKafkaBridgePortDefault
    logger.info("Connecting to Stove Kafka Bridge on port $onPort")
    return Try { GrpcUtils.createClient(onPort, StoveKafkaCoroutineScope) }
      .map {
        logger.info("Stove Kafka Observer Client created on port $onPort")
        it
      }.getOrElse { error("failed to connect Stove Kafka observer client") }
  }
}
