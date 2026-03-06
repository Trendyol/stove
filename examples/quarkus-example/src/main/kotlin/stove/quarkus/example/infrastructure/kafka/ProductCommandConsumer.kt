package stove.quarkus.example.infrastructure.kafka

import io.opentelemetry.instrumentation.annotations.WithSpan
import io.smallrye.common.annotation.Blocking
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.reactive.messaging.Incoming
import stove.quarkus.example.application.CreateProductCommand
import stove.quarkus.example.application.ProductCreator
import stove.quarkus.example.application.toCreateRequest

@ApplicationScoped
class ProductCommandConsumer(
  private val productCreator: ProductCreator
) {
  @Incoming("product-create")
  @Blocking
  @WithSpan("ProductCommandConsumer.consume")
  fun consume(command: CreateProductCommand) {
    productCreator.create(command.toCreateRequest())
  }
}
