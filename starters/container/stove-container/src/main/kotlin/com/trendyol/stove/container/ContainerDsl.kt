package com.trendyol.stove.container

import com.trendyol.stove.containers.DEFAULT_REGISTRY
import com.trendyol.stove.system.WithDsl
import com.trendyol.stove.system.abstractions.ReadyStove
import com.trendyol.stove.system.application.ArgsProvider
import com.trendyol.stove.system.application.EnvProvider
import org.testcontainers.containers.GenericContainer
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

fun WithDsl.containerApp(
  image: String,
  target: ContainerTarget,
  registry: String = DEFAULT_REGISTRY,
  compatibleSubstitute: String? = null,
  command: List<String> = emptyList(),
  envProvider: EnvProvider = EnvProvider.empty(),
  argsProvider: ArgsProvider = ArgsProvider.empty(),
  beforeStarted: suspend (configurations: Map<String, String>) -> Unit = {},
  configureContainer: GenericContainer<*>.() -> Unit = {},
  gracefulShutdownTimeout: Duration = 5.seconds
): ReadyStove {
  stove.applicationUnderTest(
    ContainerApplicationUnderTest(
      image = image,
      target = target,
      registry = registry,
      compatibleSubstitute = compatibleSubstitute,
      command = command,
      envProvider = envProvider,
      argsProvider = argsProvider,
      beforeStarted = beforeStarted,
      configureContainer = configureContainer,
      gracefulShutdownTimeout = gracefulShutdownTimeout
    )
  )
  return stove
}
