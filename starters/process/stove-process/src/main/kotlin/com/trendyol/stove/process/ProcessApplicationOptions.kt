package com.trendyol.stove.process

import com.trendyol.stove.system.*
import com.trendyol.stove.system.annotations.StoveDsl
import java.io.File
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Describes what kind of process is being tested and how to verify its readiness.
 *
 * ## Server vs Worker
 *
 * - [Server]: Listens on a port (HTTP APIs, gRPC servers, TCP servers).
 *   Default readiness is an HTTP health check.
 * - [Worker]: Does not listen on a port (Kafka consumers, batch processors, CLI tools).
 *   Default readiness is a fixed delay.
 *
 * Both variants accept any [ReadinessStrategy], so a gRPC server can use
 * [ReadinessStrategy.TcpPort] and a worker with a health endpoint can use
 * [ReadinessStrategy.HttpGet].
 *
 * ## Usage
 *
 * ```kotlin
 * // HTTP API — default health check
 * ProcessTarget.Server(port = 8080, portEnvVar = "APP_PORT")
 *
 * // gRPC server — TCP readiness
 * ProcessTarget.Server(
 *     port = 50051,
 *     portEnvVar = "GRPC_PORT",
 *     readiness = ReadinessStrategy.TcpPort(port = 50051),
 * )
 *
 * // Kafka consumer — fixed delay
 * ProcessTarget.Worker()
 *
 * // Worker with custom probe
 * ProcessTarget.Worker(
 *     readiness = ReadinessStrategy.Probe { File("/tmp/ready").exists() }
 * )
 * ```
 *
 * @see ReadinessStrategy
 * @see ProcessApplicationOptions
 */
sealed interface ProcessTarget {
  val readiness: ReadinessStrategy

  /**
   * A process that listens on a network port (HTTP, gRPC, TCP).
   *
   * @param port The port the process listens on.
   * @param portEnvVar The environment variable name used to pass the port to the process.
   * @param readiness How to verify the process is ready. Defaults to HTTP health check at `/health`.
   */
  data class Server(
    val port: Int,
    val portEnvVar: String = "PORT",
    override val readiness: ReadinessStrategy =
      ReadinessStrategy.HttpGet(HealthCheckOptions(url = "http://localhost:$port/health"))
  ) : ProcessTarget

  /**
   * A process without a network port (consumers, workers, CLI tools).
   *
   * @param readiness How to verify the process is ready. Defaults to a 2-second fixed delay.
   */
  data class Worker(
    override val readiness: ReadinessStrategy = ReadinessStrategy.FixedDelay()
  ) : ProcessTarget
}

/**
 * Options for running an OS process as the application under test.
 *
 * Works with **any language** — Go, Python, Rust, Node.js, Java CLI, etc.
 * The process is started via [ProcessBuilder], configured with environment
 * variables from [envProvider], and verified via the [target]'s readiness strategy.
 *
 * ## Example
 *
 * ```kotlin
 * // Environment variables (Go, Node.js, etc.)
 * processApp {
 *     ProcessApplicationOptions(
 *         command = listOf("/path/to/api-server"),
 *         target = ProcessTarget.Server(port = 8090, portEnvVar = "APP_PORT"),
 *         envProvider = envMapper {
 *             "database.host" to "DB_HOST"
 *             "database.port" to "DB_PORT"
 *             env("LOG_LEVEL", "debug")
 *         }
 *     )
 * }
 *
 * // CLI arguments (Rust, Python argparse, etc.)
 * processApp {
 *     ProcessApplicationOptions(
 *         command = listOf("/path/to/server"),
 *         target = ProcessTarget.Server(port = 8090),
 *         argsProvider = argsMapper(prefix = "--", separator = "=") {
 *             "database.host" to "db-host"   // --db-host=localhost
 *             "database.port" to "db-port"   // --db-port=5432
 *         }
 *     )
 * }
 * ```
 *
 * @param command The executable and its arguments (e.g., `listOf("/path/to/app", "--verbose")`).
 * @param target Describes the process type and how to verify readiness.
 * @param envProvider Maps Stove configurations to environment variables for the process.
 * @param argsProvider Maps Stove configurations to CLI arguments appended to the command.
 * @param beforeStarted Called after configurations are resolved but before the process is launched.
 *   Receives the resolved configuration map and the options themselves. Use this to write config files,
 *   seed directories, or perform any setup the process needs at startup.
 * @param workingDirectory Optional working directory for the process. Defaults to the JVM's current directory.
 * @param redirectErrorStream Whether to merge stderr into stdout. Defaults to `true`.
 * @param gracefulShutdownTimeout How long to wait for the process to exit after SIGTERM before force-killing.
 *
 * @see ProcessTarget
 * @see EnvProvider
 * @see ArgsProvider
 * @see envMapper
 * @see argsMapper
 */
@StoveDsl
data class ProcessApplicationOptions(
  val command: List<String>,
  val target: ProcessTarget,
  val envProvider: EnvProvider = EnvProvider.empty(),
  val argsProvider: ArgsProvider = ArgsProvider.empty(),
  val beforeStarted: suspend (
    configurations: Map<String, String>,
    options: ProcessApplicationOptions
  ) -> Unit = { _, _ -> },
  val workingDirectory: File? = null,
  val redirectErrorStream: Boolean = true,
  val gracefulShutdownTimeout: Duration = 5.seconds
)
