package com.trendyol.stove.kafka

import com.trendyol.stove.containers.*
import org.testcontainers.kafka.ConfluentKafkaContainer
import org.testcontainers.utility.DockerImageName

open class StoveKafkaContainer(
  override val imageNameAccess: DockerImageName
) : ConfluentKafkaContainer(imageNameAccess),
  StoveContainer

data class KafkaContainerOptions(
  override val registry: String = DEFAULT_REGISTRY,
  override val image: String = "confluentinc/cp-kafka",
  override val tag: String = "latest",
  val ports: List<Int> = DEFAULT_KAFKA_PORTS,
  override val compatibleSubstitute: String? = null,
  override val useContainerFn: UseContainerFn<StoveKafkaContainer> = { StoveKafkaContainer(it) },
  override val containerFn: ContainerFn<StoveKafkaContainer> = { }
) : ContainerOptions<StoveKafkaContainer> {
  companion object {
    val DEFAULT_KAFKA_PORTS = listOf(9092, 9093)
  }
}
