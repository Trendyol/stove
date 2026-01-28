package stove.spring.example4x.infrastructure.messaging.kafka

import io.opentelemetry.instrumentation.annotations.WithSpan
import kotlinx.coroutines.future.await
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component
import stove.spring.example4x.application.handlers.ProductCreatedEvent

@Component
class KafkaProducer(
  private val kafkaTemplate: KafkaTemplate<String, Any>,
  private val kafkaProperties: KafkaProperties
) {
  @WithSpan("KafkaProducer.send")
  suspend fun send(event: ProductCreatedEvent) {
    val topic = "${kafkaProperties.topicPrefix}.productCreated.1"
    kafkaTemplate.send(topic, event.id.toString(), event).await()
  }
}
