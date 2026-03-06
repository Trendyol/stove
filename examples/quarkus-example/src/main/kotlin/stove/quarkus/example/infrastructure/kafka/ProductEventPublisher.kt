package stove.quarkus.example.infrastructure.kafka

import io.opentelemetry.instrumentation.annotations.WithSpan
import io.smallrye.reactive.messaging.MutinyEmitter
import io.smallrye.reactive.messaging.kafka.KafkaRecord
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.reactive.messaging.Channel
import stove.quarkus.example.application.ProductCreatedEvent

@ApplicationScoped
class ProductEventPublisher(
  @param:Channel("product-created")
  private val emitter: MutinyEmitter<ProductCreatedEvent>
) {
  @WithSpan("ProductEventPublisher.publish")
  fun publish(event: ProductCreatedEvent) {
    emitter.sendMessageAndAwait(
      KafkaRecord
        .of(event.id.toString(), event)
        .withHeader("X-EventType", ProductCreatedEvent::class.simpleName!!)
    )
  }
}
