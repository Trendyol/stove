package com.trendyol.stove.testing.e2e.kafka

import kotlinx.coroutines.future.await
import org.apache.kafka.clients.producer.ProducerRecord
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.util.concurrent.ListenableFuture
import java.util.concurrent.CompletableFuture

/**
 * Sends a [ProducerRecord] using the [KafkaTemplate] and waits for the result.
 * This method is used to send a record to Kafka in a compatible way with different versions of Spring Kafka.
 * Supports Spring-Kafka 2.x and 3.x
 */
suspend fun <K, V> KafkaTemplate<K, V>.sendCompatible(record: ProducerRecord<K, V>) {
    val method = this::class.java.getDeclaredMethod("send", ProducerRecord::class.java).apply { isAccessible = true }
    when (method.returnType.kotlin) {
        CompletableFuture::class -> (method.invoke(this, record) as CompletableFuture<*>).await()
        ListenableFuture::class -> (method.invoke(this, record) as ListenableFuture<*>).completable().await()
        else -> throw IllegalStateException("Unsupported return type for KafkaTemplate.send method: ${method.returnType}")
    }
}
