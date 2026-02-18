package stove.spring.streams.example.kafka

import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.streams.errors.*
import org.slf4j.LoggerFactory

class CustomDeserializationExceptionHandler : DeserializationExceptionHandler {
  companion object {
    private val logger = LoggerFactory.getLogger(CustomDeserializationExceptionHandler::class.java)
  }

  override fun handleError(
    context: ErrorHandlerContext,
    record: ConsumerRecord<ByteArray?, ByteArray>,
    exception: Exception?
  ): DeserializationExceptionHandler.Response {
    logger.error(
      "Deserialization exception in [${record.topic()}]: [${exception?.message}] Caused by: ${exception?.cause?.message}"
    )
    return DeserializationExceptionHandler.Response.resume()
  }

  override fun configure(configs: MutableMap<String, *>?) = Unit
}
