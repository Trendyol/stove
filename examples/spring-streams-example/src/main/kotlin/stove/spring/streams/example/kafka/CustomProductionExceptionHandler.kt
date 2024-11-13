package stove.spring.streams.example.kafka

import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.streams.errors.ProductionExceptionHandler
import org.slf4j.LoggerFactory
import java.lang.Exception

class CustomProductionExceptionHandler : ProductionExceptionHandler {
  companion object {
    private val logger = LoggerFactory.getLogger(CustomProductionExceptionHandler::class.java)
  }

  @Deprecated("Deprecated")
  override fun handle(
    record: ProducerRecord<ByteArray, ByteArray>,
    exception: Exception
  ): ProductionExceptionHandler.ProductionExceptionHandlerResponse {
    logger.error(
      "Deserialization exception in [${record.topic()}]: [${exception.message}] Caused by: ${exception.cause?.message}"
    )
    return ProductionExceptionHandler.ProductionExceptionHandlerResponse.CONTINUE
  }

  override fun configure(configs: MutableMap<String, *>) {
  }
}
