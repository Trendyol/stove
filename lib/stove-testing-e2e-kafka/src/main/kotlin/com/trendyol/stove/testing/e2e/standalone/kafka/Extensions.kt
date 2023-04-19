package com.trendyol.stove.testing.e2e.standalone.kafka

import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.clients.producer.RecordMetadata
import java.util.Properties
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

suspend inline fun <reified K : Any, reified V : Any> KafkaProducer<K, V>.dispatch(record: ProducerRecord<K, V>): RecordMetadata =
    suspendCoroutine { continuation ->
        send(record) { metadata, exception ->
            if (metadata == null) {
                continuation.resumeWithException(exception!!)
            } else {
                continuation.resume(metadata)
            }
        }
    }

fun <K, V> Map<K, V>.toProperties(): Properties = Properties().apply {
    this@toProperties.forEach { (k, v) -> this[k] = v }
}
