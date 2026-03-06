package stove.quarkus.example.infrastructure.kafka

import org.apache.kafka.clients.producer.ProducerInterceptor
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.clients.producer.RecordMetadata

const val USER_EMAIL_HEADER = "X-UserEmail"
const val DEFAULT_USER_EMAIL_HEADER_VALUE = "stove@trendyol.com"

class CustomProducerInterceptor : ProducerInterceptor<String, Any> {
  override fun onSend(record: ProducerRecord<String, Any>): ProducerRecord<String, Any> {
    if (record.headers().lastHeader(USER_EMAIL_HEADER) == null) {
      record.headers().add(USER_EMAIL_HEADER, DEFAULT_USER_EMAIL_HEADER_VALUE.toByteArray())
    }
    return record
  }

  override fun configure(configs: MutableMap<String, *>?) = Unit

  override fun onAcknowledgement(
    metadata: RecordMetadata?,
    exception: Exception?
  ) = Unit

  override fun close() = Unit
}
