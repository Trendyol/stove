package com.trendyol.stove.containers

import arrow.core.*
import com.github.dockerjava.api.DockerClient
import com.github.dockerjava.api.async.ResultCallback
import com.github.dockerjava.api.model.*
import com.trendyol.stove.system.abstractions.SystemRuntime
import org.testcontainers.DockerClientFactory
import org.testcontainers.utility.DockerImageName
import java.io.ByteArrayOutputStream
import java.util.concurrent.*

/**
 * Interface for Stove-managed Docker containers with extended functionality.
 *
 * This interface wraps Testcontainers and provides additional capabilities like:
 * - Pausing/unpausing containers for fault injection tests
 * - Executing commands inside running containers
 * - Inspecting container state
 *
 * ## Implemented By
 *
 * All Stove database and infrastructure containers implement this interface:
 * - PostgreSQL, MongoDB, Couchbase, Elasticsearch, MSSQL, Redis containers
 * - Kafka containers
 *
 * ## Pause/Unpause for Fault Injection
 *
 * Simulate network partitions or service unavailability:
 *
 * ```kotlin
 * stove {
 *     // Pause the database to simulate outage
 *     postgresql {
 *         pause()
 *     }
 *
 *     // Test application behavior during outage
 *     http {
 *         getResponse("/health") { response ->
 *             response.status shouldBe 503
 *         }
 *     }
 *
 *     // Restore the database
 *     postgresql {
 *         unpause()
 *     }
 *
 *     // Verify recovery
 *     http {
 *         getResponse("/health") { response ->
 *             response.status shouldBe 200
 *         }
 *     }
 * }
 * ```
 *
 * ## Execute Commands Inside Container
 *
 * Run commands inside the container for debugging or setup:
 *
 * ```kotlin
 * postgresql {
 *     val result = execCommand("psql", "-U", "test", "-c", "SELECT 1")
 *     result.exitCode shouldBe 0
 *     result.stdout shouldContain "1"
 * }
 * ```
 *
 * ## Inspect Container State
 *
 * Check container health and status:
 *
 * ```kotlin
 * couchbase {
 *     val info = inspect()
 *     info.running shouldBe true
 *     info.paused shouldBe false
 * }
 * ```
 *
 * @see SystemRuntime
 * @see ExecResult
 * @see StoveContainerInspectInformation
 */
interface StoveContainer : SystemRuntime {
  val imageNameAccess: DockerImageName

  val containerIdAccess: String
    get() = dockerClientAccess.value
      .listContainersCmd()
      .exec()
      .firstOrNone { it.image == imageNameAccess.asCanonicalNameString() }
      .getOrElse { error("Container with image ${imageNameAccess.asCanonicalNameString()} not found") }
      .id

  val dockerClientAccess: Lazy<DockerClient>
    get() = lazy { DockerClientFactory.lazyClient() }

  /**
   * Pauses the container. This method is idempotent - if the container is already paused, it does nothing.
   */
  fun pause() {
    if (!inspect().paused) {
      dockerClientAccess.value.pauseContainerCmd(containerIdAccess).exec()
    }
  }

  /**
   * Unpauses the container. This method is idempotent - if the container is not paused, it does nothing.
   */
  fun unpause() {
    if (inspect().paused) {
      dockerClientAccess.value.unpauseContainerCmd(containerIdAccess).exec()
    }
  }

  /**
   * Executes a command inside the running container using Docker client directly.
   * This method works even when the testcontainer instance wasn't started (e.g., on subsequent runs with reuse).
   *
   * @param command The command and its arguments to execute
   * @param timeoutSeconds Maximum time to wait for command completion (default: 60 seconds)
   * @return [ExecResult] containing exit code, stdout, and stderr
   */
  fun execCommand(
    vararg command: String,
    timeoutSeconds: Long = 60
  ): ExecResult {
    val docker = dockerClientAccess.value
    val containerId = containerIdAccess

    // Create exec instance
    val execCreate = docker
      .execCreateCmd(containerId)
      .withAttachStdout(true)
      .withAttachStderr(true)
      .withCmd(*command)
      .exec()

    val stdout = ByteArrayOutputStream()
    val stderr = ByteArrayOutputStream()
    val latch = CountDownLatch(1)

    // Start exec and capture output
    docker
      .execStartCmd(execCreate.id)
      .exec(object : ResultCallback.Adapter<Frame>() {
        override fun onNext(frame: Frame) {
          when (frame.streamType) {
            StreamType.STDOUT -> {
              stdout.write(frame.payload)
            }

            StreamType.STDERR -> {
              stderr.write(frame.payload)
            }

            else -> {} // Ignore other stream types
          }
        }

        override fun onComplete() {
          latch.countDown()
        }

        override fun onError(throwable: Throwable) {
          latch.countDown()
        }
      })

    if (!latch.await(timeoutSeconds, TimeUnit.SECONDS)) {
      return ExecResult(
        exitCode = -1,
        stdout = stdout.toString(Charsets.UTF_8),
        stderr = "Command timed out after $timeoutSeconds seconds"
      )
    }

    val execInspect = docker.inspectExecCmd(execCreate.id).exec()
    val exitCode = execInspect.exitCodeLong?.toInt() ?: -1

    return ExecResult(
      exitCode = exitCode,
      stdout = stdout.toString(Charsets.UTF_8),
      stderr = stderr.toString(Charsets.UTF_8)
    )
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
        running = it.state.running ?: false,
        paused = it.state.paused ?: false,
        restarting = it.state.restarting ?: false,
        startedAt = it.state.startedAt.toString(),
        finishedAt = it.state.finishedAt.toString(),
        exitCode = it.state.exitCodeLong ?: 0,
        error = it.state.error.toString()
      )
    }
}

/**
 * Result of executing a command in a container.
 */
data class ExecResult(
  val exitCode: Int,
  val stdout: String,
  val stderr: String
)

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
