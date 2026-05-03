package com.trendyol.stove.container

import com.trendyol.stove.containers.DEFAULT_REGISTRY
import com.trendyol.stove.system.ReadinessStrategy
import com.trendyol.stove.system.annotations.StoveDsl
import com.trendyol.stove.system.application.ArgsProvider
import com.trendyol.stove.system.application.EnvProvider
import org.testcontainers.containers.GenericContainer
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

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

@StoveDsl
data class ContainerApplicationOptions(
  val image: String,
  val target: ContainerTarget,
  val registry: String = DEFAULT_REGISTRY,
  val compatibleSubstitute: String? = null,
  val command: List<String> = emptyList(),
  val envProvider: EnvProvider = EnvProvider.empty(),
  val argsProvider: ArgsProvider = ArgsProvider.empty(),
  val beforeStarted: suspend (
    configurations: Map<String, String>,
    options: ContainerApplicationOptions
  ) -> Unit = { _, _ -> },
  val configureContainer: GenericContainer<*>.() -> Unit = {},
  val gracefulShutdownTimeout: Duration = 5.seconds
)

data class ContainerApplicationContext(
  val container: GenericContainer<*>
)
