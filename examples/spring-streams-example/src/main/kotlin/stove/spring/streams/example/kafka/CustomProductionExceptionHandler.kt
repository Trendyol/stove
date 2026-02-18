package stove.spring.streams.example.kafka

import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.streams.errors.*
import org.slf4j.LoggerFactory

class CustomProductionExceptionHandler : ProductionExceptionHandler {
  companion object {
    private val logger = LoggerFactory.getLogger(CustomProductionExceptionHandler::class.java)
  }

  override fun handleError(
    context: ErrorHandlerContext?,
    record: ProducerRecord<ByteArray?, ByteArray?>?,
    exception: Exception?
  ): ProductionExceptionHandler.Response? {
    logger.error(
      "Production exception in [${record?.topic()}]: [${exception?.message}] Caused by: ${exception?.cause?.message}"
    )
    return ProductionExceptionHandler.Response.resume()
  }

  override fun configure(configs: MutableMap<String, *>) = Unit
}
