package stove.quarkus.example.infrastructure.kafka

import io.smallrye.common.annotation.Identifier
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Produces
import org.eclipse.microprofile.config.inject.ConfigProperty

@ApplicationScoped
class KafkaClientConfiguration {
  @ConfigProperty(name = "app.kafka.bridge-interceptor-class", defaultValue = "")
  lateinit var bridgeInterceptorClass: String

  @Produces
  @Identifier("product-create")
  fun productCreateConfiguration(): Map<String, Any> = buildMap {
    put("auto.offset.reset", "earliest")
    put("allow.auto.create.topics", true)
    bridgeInterceptorClass.takeIf { it.isNotBlank() }?.let {
      put("interceptor.classes", it)
    }
  }

  @Produces
  @Identifier("product-created")
  fun productCreatedConfiguration(): Map<String, Any> = buildMap {
    put("acks", "1")
    put("interceptor.classes", producerInterceptors())
  }

  private fun producerInterceptors(): String = buildList {
    add(CustomProducerInterceptor::class.java.name)
    bridgeInterceptorClass.takeIf { it.isNotBlank() }?.let(::add)
  }.joinToString(",")
}
