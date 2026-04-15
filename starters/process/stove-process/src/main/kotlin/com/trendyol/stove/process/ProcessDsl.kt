package com.trendyol.stove.process

import com.trendyol.stove.system.WithDsl
import com.trendyol.stove.system.abstractions.ReadyStove

/**
 * Registers an OS-process-based application under test.
 *
 * Works with **any language** — Go, Python, Rust, Node.js, Java CLI, etc.
 * The process is started with environment variables derived from Stove's
 * infrastructure configurations, and readiness is verified via the target's
 * [ReadinessStrategy][com.trendyol.stove.system.ReadinessStrategy].
 *
 * ## Example
 *
 * ```kotlin
 * Stove().with {
 *     httpClient { HttpClientSystemOptions(baseUrl = "http://localhost:8090") }
 *     postgresql { PostgresqlOptions(...) }
 *
 *     processApp {
 *         ProcessApplicationOptions(
 *             command = listOf("/path/to/server"),
 *             target = ProcessTarget.Server(port = 8090, portEnvVar = "APP_PORT"),
 *             envProvider = envMapper {
 *                 "database.host" to "DB_HOST"
 *                 "database.port" to "DB_PORT"
 *             }
 *         )
 *     }
 * }.run()
 * ```
 *
 * @param configure Configuration block that returns [ProcessApplicationOptions].
 * @return [ReadyStove] to chain with `.run()`.
 * @see ProcessApplicationOptions
 * @see ProcessTarget
 */
fun WithDsl.processApp(configure: () -> ProcessApplicationOptions): ReadyStove {
  this.stove.applicationUnderTest(ProcessApplicationUnderTest(configure()))
  return this.stove
}

/**
 * Convenience extension for Go applications.
 *
 * Defaults the binary path from the `go.app.binary` system property, which is
 * typically set by the Gradle build task that compiles the Go binary.
 *
 * ## Example
 *
 * ```kotlin
 * Stove().with {
 *     httpClient { HttpClientSystemOptions(baseUrl = "http://localhost:8090") }
 *     postgresql { PostgresqlOptions(...) }
 *
 *     goApp(
 *         target = ProcessTarget.Server(port = 8090, portEnvVar = "APP_PORT"),
 *         envProvider = envMapper {
 *             "database.host" to "DB_HOST"
 *             "database.port" to "DB_PORT"
 *         }
 *     )
 * }.run()
 * ```
 *
 * @param binaryPath Path to the compiled Go binary. Defaults to `go.app.binary` system property.
 * @param target The process target (Server or Worker) with readiness strategy.
 * @param envProvider Maps Stove configurations to environment variables.
 * @return [ReadyStove] to chain with `.run()`.
 */
fun WithDsl.goApp(
  binaryPath: String = System.getProperty("go.app.binary")
    ?: error("go.app.binary system property not set"),
  target: ProcessTarget,
  envProvider: EnvProvider = EnvProvider.empty()
): ReadyStove = processApp {
  ProcessApplicationOptions(
    command = listOf(binaryPath),
    target = target,
    envProvider = envProvider
  )
}
