package com.trendyol.stove.testing.e2e.kafka

import kotlinx.coroutines.future.await
import org.apache.kafka.clients.producer.ProducerRecord
import org.springframework.kafka.core.KafkaTemplate
import java.util.concurrent.CompletableFuture

/**
 * Sends a [ProducerRecord] using the [KafkaTemplate] and waits for the result.
 * This method is used to send a record to Kafka in a compatible way with different versions of Spring Kafka.
 *
 * Supports:
 * - Spring Kafka 2.x (ListenableFuture)
 * - Spring Kafka 3.x (CompletableFuture with ListenableFuture backward compatibility)
 * - Spring Kafka 4.x (CompletableFuture only)
 *
 * Uses reflection to avoid compile-time dependency on ListenableFuture which doesn't exist in Spring 4.x.
 */
suspend fun KafkaTemplate<*, *>.sendCompatible(record: ProducerRecord<*, *>) {
  val method = this::class.java.getDeclaredMethod("send", ProducerRecord::class.java).apply { isAccessible = true }
  val returnType = method.returnType
  val result = method.invoke(this, record)

  when {
    CompletableFuture::class.java.isAssignableFrom(returnType) -> {
      (result as CompletableFuture<*>).await()
    }

    returnType.name == "org.springframework.util.concurrent.ListenableFuture" -> {
      // Use reflection to call completable() method for Spring Kafka 2.x/3.x ListenableFuture
      val completableMethod = result.javaClass.getMethod("completable")
      (completableMethod.invoke(result) as CompletableFuture<*>).await()
    }

    else -> {
      error("Unsupported return type for KafkaTemplate.send method: $returnType")
    }
  }
}

/**
 * Default KafkaOps that uses the compatible send method.
 * Works with Spring Kafka 2.x, 3.x, and 4.x.
 */
fun defaultKafkaOps(): KafkaOps = KafkaOps(
  send = { kafkaTemplate, record -> kafkaTemplate.sendCompatible(record) }
)
