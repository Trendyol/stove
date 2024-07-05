package com.trendyol.stove.testing.e2e.standalone.kafka.setup.example.consumers

import arrow.core.*
import com.trendyol.stove.testing.e2e.standalone.kafka.setup.example.KafkaTestShared.TopicDefinition
import com.trendyol.stove.testing.e2e.standalone.kafka.setup.example.StoveListener
import io.github.nomisRev.kafka.publisher.PublisherSettings
import org.apache.kafka.clients.consumer.ConsumerRecord

// TODO: Convert into in-flight consumer
@Suppress("TooGenericExceptionCaught", "TooGenericExceptionThrown")
class ProductFailingConsumer(
  consumerSettings: Map<String, Any>,
  producerSettings: PublisherSettings<String, Any>
) : StoveListener(consumerSettings, producerSettings) {
  override val topicDefinition: TopicDefinition = TopicDefinition(
    "productFailing",
    "productFailing.retry",
    "productFailing.error"
  )

  override suspend fun listen(record: ConsumerRecord<String, String>) {
    record.headers().firstOrNone { it.key() == "doNotFail" }
      .onSome { return }
      .onNone { throw Exception("exception occurred on purpose") }
  }
}

fun <K, V> ConsumerRecord<K, V>.getRetryCount(): Int =
  this.headers().firstOrNone { it.key() == "retry" }
    .map { it.value().toString(Charsets.UTF_8).toInt() }
    .getOrElse { 0 }

fun <K, V> ConsumerRecord<K, V>.incrementRetryCount(): Int {
  val currentRetry = this.getRetryCount()
  this.headers().remove("retry")
  this.headers().add("retry", (currentRetry + 1).toString().toByteArray(Charsets.UTF_8))
  return currentRetry + 1
}
