package stove.spring.streams.example.kafka

import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.streams.errors.DeserializationExceptionHandler
import org.apache.kafka.streams.errors.DeserializationExceptionHandler.DeserializationHandlerResponse
import org.apache.kafka.streams.processor.ProcessorContext
import org.slf4j.LoggerFactory
import java.lang.Exception

class CustomDeserializationExceptionHandler : DeserializationExceptionHandler {
  companion object {
    private val logger = LoggerFactory.getLogger(CustomDeserializationExceptionHandler::class.java)
  }

  override fun handle(
    context: ProcessorContext?,
    record: ConsumerRecord<ByteArray, ByteArray>?,
    exception: Exception?
  ): DeserializationHandlerResponse {
    logger.error(
      "Deserialization exception in [${record?.topic()}]: [${exception?.message}] Caused by: ${exception?.cause?.message}"
    )
    return DeserializationHandlerResponse.CONTINUE
  }

  override fun configure(configs: MutableMap<String, *>?) {
  }
}
