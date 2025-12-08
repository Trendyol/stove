package com.trendyol.stove.testing.e2e.kafka

import kotlinx.coroutines.future.await
import org.apache.kafka.clients.producer.ProducerRecord
import org.springframework.kafka.core.KafkaTemplate
import java.util.concurrent.CompletableFuture

/**
 * Sends a [ProducerRecord] using the [KafkaTemplate] and waits for the result.
 * This method is used to send a record to Kafka in a compatible way with Spring Kafka 4.x
 */
suspend fun KafkaTemplate<*, *>.sendCompatible(record: ProducerRecord<*, *>) {
  val method = this::class.java.getDeclaredMethod("send", ProducerRecord::class.java).apply { isAccessible = true }
  when (method.returnType.kotlin) {
    CompletableFuture::class -> (method.invoke(this, record) as CompletableFuture<*>).await()
    else -> error("Unsupported return type for KafkaTemplate.send method: ${method.returnType}")
  }
}
