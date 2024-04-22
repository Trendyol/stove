package stove.spring.example.infrastructure.messaging.kafka

import kotlinx.coroutines.future.await
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.header.internals.RecordHeader
import org.slf4j.*
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component

data class KafkaOutgoingMessage(
  val topic: String,
  val key: String?,
  val payload: String,
  val headers: Map<String, String>,
  val partition: Int? = null
)

@Component
class KafkaProducer(
  private val kafkaTemplate: KafkaTemplate<String, Any>
) {
  private val logger: Logger = LoggerFactory.getLogger(KafkaProducer::class.java)

  suspend fun send(message: KafkaOutgoingMessage) {
    val recordHeaders = message.headers.map { it ->
      RecordHeader(
        it.key,
        it.value.toByteArray()
      )
    }.toMutableList()
    val record = ProducerRecord<String, Any>(
      message.topic,
      message.partition,
      message.key,
      message.payload,
      recordHeaders
    )
    logger.info("Kafka message has published $message")

    kafkaTemplate.send(record).await()
  }
}
