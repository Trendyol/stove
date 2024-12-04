package com.trendyol.stove.testing.e2e.containers

import arrow.core.*
import com.github.dockerjava.api.DockerClient
import org.testcontainers.DockerClientFactory
import org.testcontainers.utility.DockerImageName

/**
 * Represents a container and provides access to its properties, such as image name and container ID.
 * Also provides methods that are helpful for Stove operations.
 */
interface StoveContainer {
  val imageNameAccess: DockerImageName

  val containerIdAccess: String
    get() = dockerClientAccess.value
      .listContainersCmd()
      .exec()
      .first {
        it.image == imageNameAccess.asCanonicalNameString()
      }.id

  val dockerClientAccess: Lazy<DockerClient>
    get() = lazy { DockerClientFactory.lazyClient() }

  fun pause() {
    dockerClientAccess.value.pauseContainerCmd(containerIdAccess).exec()
  }

  fun unpause() {
    dockerClientAccess.value.unpauseContainerCmd(containerIdAccess).exec()
  }

  fun inspect(): StoveContainerInspectInformation = dockerClientAccess.value
    .inspectContainerCmd(containerIdAccess)
    .exec()
    .let {
      StoveContainerInspectInformation(
        id = it.id,
        labels = it.config.labels ?: emptyMap(),
        name = it.name,
        state = it.state.toString(),
        running = it.state.running
          .toOption()
          .getOrElse { false },
        paused = it.state.paused
          .toOption()
          .getOrElse { false },
        restarting = it.state.restarting
          .toOption()
          .getOrElse { false },
        startedAt = it.state.startedAt.toString(),
        finishedAt = it.state.finishedAt.toString(),
        exitCode = it.state.exitCodeLong
          .toOption()
          .getOrElse { 0 },
        error = it.state.error.toString()
      )
    }
}

data class StoveContainerInspectInformation(
  val id: String,
  val labels: Map<String, String>,
  val name: String,
  val state: String,
  val running: Boolean,
  val paused: Boolean,
  val restarting: Boolean,
  val startedAt: String,
  val finishedAt: String,
  val exitCode: Long,
  val error: String
)
