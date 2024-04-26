package com.trendyol.stove.testing.e2e.standalone.kafka

import com.trendyol.stove.testing.e2e.containers.ContainerFn
import com.trendyol.stove.testing.e2e.containers.ContainerOptions
import com.trendyol.stove.testing.e2e.containers.DEFAULT_REGISTRY
import org.testcontainers.containers.KafkaContainer

data class KafkaContainerOptions(
  override val registry: String = DEFAULT_REGISTRY,
  override val image: String = "confluentinc/cp-kafka",
  override val tag: String = "latest",
  val ports: List<Int> = DEFAULT_KAFKA_PORTS,
  override val compatibleSubstitute: String? = null,
  override val containerFn: ContainerFn<KafkaContainer> = { }
) : ContainerOptions {
  companion object {
    val DEFAULT_KAFKA_PORTS = listOf(9092, 9093)
  }
}
