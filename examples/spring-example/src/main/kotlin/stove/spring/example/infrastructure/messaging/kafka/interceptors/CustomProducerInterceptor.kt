package stove.spring.example.infrastructure.messaging.kafka.interceptors

import java.time.Instant
import java.util.UUID
import org.apache.kafka.clients.producer.ProducerInterceptor
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.clients.producer.RecordMetadata
import stove.spring.example.infrastructure.Defaults
import stove.spring.example.infrastructure.Headers

class CustomProducerInterceptor : ProducerInterceptor<String, Any> {
    companion object {
        private var DEFAULT_HOST_NAME_AS_BYTE: ByteArray = Defaults.HOST_NAME.toByteArray()
    }

    override fun onSend(record: ProducerRecord<String, Any>): ProducerRecord<String, Any> {
        val messageId = UUID.randomUUID().toString()
        record.headers().add(Headers.MESSAGE_ID_KEY, messageId.toByteArray())
        record.headers().add(Headers.PUBLISHED_DATE_KEY, Instant.now().toString().toByteArray())
        record.headers().add(Headers.HOST_KEY, DEFAULT_HOST_NAME_AS_BYTE)
        record.headers().add(
            Headers.CORRELATION_ID_KEY,
            Headers.getOrDefault(Headers.CORRELATION_ID_KEY, messageId).toByteArray()
        )
        record.headers().add(Headers.AGENT_NAME_KEY, Defaults.AGENT_NAME.toByteArray())
        record.headers().add(
            Headers.USER_EMAIL_KEY,
            Headers.getOrDefault(Headers.USER_EMAIL_KEY, Defaults.USER_EMAIL).toByteArray()
        )
        return record
    }

    override fun configure(configs: MutableMap<String, *>?) = Unit

    override fun onAcknowledgement(
        metadata: RecordMetadata?,
        exception: Exception?,
    ) = Unit

    override fun close() = Unit
}
