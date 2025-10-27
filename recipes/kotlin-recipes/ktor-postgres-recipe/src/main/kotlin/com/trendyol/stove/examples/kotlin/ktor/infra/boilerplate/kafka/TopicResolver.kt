package com.trendyol.stove.examples.kotlin.ktor.infra.boilerplate.kafka

import com.trendyol.stove.examples.kotlin.ktor.application.*

class TopicResolver(
  private val kafkaConfiguration: KafkaConfiguration
) {
  operator fun invoke(aggregateName: String): Topic = kafkaConfiguration.topics.getValue(aggregateName)
}
