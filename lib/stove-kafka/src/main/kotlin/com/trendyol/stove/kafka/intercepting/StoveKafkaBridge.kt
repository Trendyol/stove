package com.trendyol.stove.kafka.intercepting

import com.trendyol.stove.kafka.*
import com.trendyol.stove.serialization.StoveSerde
import kotlinx.coroutines.*
import okio.ByteString.Companion.toByteString
import org.apache.kafka.clients.consumer.*
import org.apache.kafka.clients.producer.*
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.header.Headers
import org.slf4j.Logger
import java.nio.charset.Charset
import java.util.*

/**
 * Kafka client interceptor that forwards observed messages to Stove's unary observer service.
 *
 * The service contract is shared with the published Go bridge, so these calls intentionally use
 * the original message DTOs and RPC names.
 */
@Suppress("UNUSED")
class StoveKafkaBridge<K, V> :
  ConsumerInterceptor<K, V>,
  ProducerInterceptor<K, V> {
  private val logger: Logger = org.slf4j.LoggerFactory.getLogger(StoveKafkaBridge::class.java)
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private var bridgePort: String = stoveKafkaBridgePortDefault
  private var serde: StoveSerde<Any, ByteArray> = stoveSerdeRef
  private val clientHandle = lazy { startGrpcClient() }
  private val client: StoveKafkaObserverServiceClient
    get() = clientHandle.value.client

  override fun configure(configs: MutableMap<String, *>) {
    bridgePort = configs[KafkaBridgeConfig.BRIDGE_PORT_CONFIG]
      ?.toString()
      ?.takeIf(String::isNotBlank)
      ?: System.getenv(STOVE_KAFKA_BRIDGE_PORT)
      ?: System.getProperty(STOVE_KAFKA_BRIDGE_PORT)
      ?: stoveKafkaBridgePortDefault
    val bridgeId = configs[KafkaBridgeConfig.BRIDGE_ID_CONFIG]
      ?.toString()
      ?.takeIf(String::isNotBlank)
    serde = bridgeId?.let(KafkaBridgeRuntimeRegistry::serde) ?: stoveSerdeRef
  }

  override fun onSend(record: ProducerRecord<K, V>): ProducerRecord<K, V> =
    record.also { send(it.toPublishedMessage()) }

  override fun onConsume(records: ConsumerRecords<K, V>): ConsumerRecords<K, V> =
    records.also { records.forEach { record -> send(record.toConsumedMessage()) } }

  override fun onCommit(offsets: MutableMap<TopicPartition, OffsetAndMetadata>) {
    offsets.forEach { (topicPartition, offset) -> send(topicPartition.toCommittedMessage(offset)) }
  }

  override fun onAcknowledgement(
    metadata: RecordMetadata?,
    exception: Exception?
  ) = send(metadata.toAcknowledgedMessage(exception))

  override fun close() {
    if (clientHandle.isInitialized()) clientHandle.value.close()
    scope.cancel()
  }

  private fun send(message: ConsumedMessage) = runBlocking {
    report("consumed", message) { client.onConsumedMessage().execute(message) }
  }

  private fun send(message: PublishedMessage) = runBlocking {
    report("published", message) { client.onPublishedMessage().execute(message) }
  }

  private fun send(message: CommittedMessage) = runBlocking {
    report("committed", message) { client.onCommittedMessage().execute(message) }
  }

  private fun send(message: AcknowledgedMessage) = runBlocking {
    report("acknowledged", message) { client.onAcknowledgedMessage().execute(message) }
  }

  private suspend fun report(
    kind: String,
    message: Any,
    call: suspend () -> Reply
  ) {
    runCatching { call() }
      .onFailure { error -> logger.error("Failed to send {} message to Stove Kafka Bridge: {}", kind, message, error) }
  }

  private fun ProducerRecord<K, V>.toPublishedMessage(): PublishedMessage = PublishedMessage(
    id = UUID.randomUUID().toString(),
    key = key().toString(),
    message = serialize(value()).toByteString(),
    topic = topic(),
    headers = headers().textHeaders()
  )

  private fun ConsumerRecord<K, V>.toConsumedMessage(): ConsumedMessage = ConsumedMessage(
    id = UUID.randomUUID().toString(),
    key = key().toString(),
    message = serialize(value()).toByteString(),
    topic = topic(),
    offset = offset(),
    partition = partition(),
    headers = headers().textHeaders()
  )

  private fun TopicPartition.toCommittedMessage(offset: OffsetAndMetadata): CommittedMessage = CommittedMessage(
    id = UUID.randomUUID().toString(),
    topic = topic(),
    partition = partition(),
    offset = offset.offset(),
    metadata = offset.metadata()
  )

  private fun RecordMetadata?.toAcknowledgedMessage(exception: Exception?): AcknowledgedMessage = AcknowledgedMessage(
    id = UUID.randomUUID().toString(),
    topic = this?.topic().orEmpty(),
    partition = this?.partition() ?: -1,
    offset = this?.offset() ?: -1L,
    exception = exception?.message.orEmpty()
  )

  private fun serialize(value: V?): ByteArray = when (value) {
    null -> byteArrayOf()
    is ByteArray -> value
    else -> serde.serialize(value as Any)
  }

  private fun Headers.textHeaders(): Map<String, String> = associate { header ->
    header.key() to (header.value()?.toString(Charset.defaultCharset()) ?: "")
  }

  private fun startGrpcClient(): StoveKafkaObserverClientHandle {
    logger.info("Connecting to Stove Kafka Bridge on port {}", bridgePort)
    return runCatching { GrpcUtils.createClientHandle(bridgePort, scope) }
      .onSuccess { logger.info("Stove Kafka Observer Client created on port {}", bridgePort) }
      .getOrElse { cause -> throw IllegalStateException("Failed to connect Stove Kafka observer client", cause) }
  }
}
