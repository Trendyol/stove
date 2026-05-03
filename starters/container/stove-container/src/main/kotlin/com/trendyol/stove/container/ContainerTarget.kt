package com.trendyol.stove.container

import com.trendyol.stove.system.ReadinessStrategy
import org.testcontainers.containers.GenericContainer

sealed interface ContainerTarget {
  val readiness: ReadinessStrategy

  data class Server(
    val hostPort: Int,
    val internalPort: Int = hostPort,
    val portEnvVar: String = "PORT",
    val bindHostPort: Boolean = true,
    override val readiness: ReadinessStrategy =
      ReadinessStrategy.HttpGet(url = "http://localhost:$hostPort/health")
  ) : ContainerTarget

  data class Worker(
    override val readiness: ReadinessStrategy = ReadinessStrategy.FixedDelay()
  ) : ContainerTarget
}

internal data class ContainerApplicationContext(
  val container: GenericContainer<*>
)
