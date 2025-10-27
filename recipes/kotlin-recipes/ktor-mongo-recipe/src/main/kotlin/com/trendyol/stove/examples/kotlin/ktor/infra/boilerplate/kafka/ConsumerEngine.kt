package com.trendyol.stove.examples.kotlin.ktor.infra.boilerplate.kafka

class ConsumerEngine(
  private val supervisors: List<ConsumerSupervisor<*, *>>
) {
  fun start() {
    supervisors.forEach { it.start() }
  }

  fun stop() {
    supervisors.forEach { it.cancel() }
  }
}
