package com.trendyol.stove.system

import com.trendyol.stove.system.abstractions.*
import com.trendyol.stove.system.annotations.StoveDsl

/**
 * Options for [ProvidedApplicationUnderTest].
 *
 * @param readiness Optional readiness strategy. If provided, Stove will verify
 *                  the remote application is reachable before running tests.
 *                  Use [ReadinessStrategy.HttpGet] for HTTP health checks,
 *                  [ReadinessStrategy.TcpPort] for gRPC/TCP, or [ReadinessStrategy.Probe]
 *                  for custom checks.
 */
data class ProvidedApplicationOptions(
  val readiness: ReadinessStrategy? = null
)

/**
 * A no-op [ApplicationUnderTest] for testing against already-deployed remote applications.
 *
 * Use this when the application under test is already running (e.g., deployed to staging/dev)
 * and you want to write Stove tests against it without starting it locally.
 *
 * The application can be written in **any language** (Go, Python, .NET, Rust, Node.js, etc.)
 * as long as it exposes HTTP/gRPC and uses infrastructure Stove can connect to.
 *
 * ## Example
 *
 * ```kotlin
 * Stove().with {
 *     httpClient {
 *         HttpClientSystemOptions(baseUrl = "https://staging.myapp.com")
 *     }
 *     providedApplication {
 *         ProvidedApplicationOptions(
 *             readiness = ReadinessStrategy.HttpGet(
 *                 url = "https://staging.myapp.com/actuator/health"
 *             )
 *         )
 *     }
 * }.run()
 * ```
 *
 * @see ProvidedApplicationOptions
 * @see ReadinessStrategy
 */
@StoveDsl
class ProvidedApplicationUnderTest(
  private val options: ProvidedApplicationOptions
) : ApplicationUnderTest<Unit> {
  override suspend fun start(configurations: List<String>) {
    options.readiness?.let { ReadinessChecker.check(it) }
  }

  override suspend fun stop(): Unit = Unit
}

/**
 * Registers a no-op application under test for testing against a remote/already-deployed application.
 *
 * HTTP and other system configurations are done separately via their own DSL functions
 * (`httpClient { }`, `kafka { }`, etc.). This function only signals that the application
 * is already running and should not be started by Stove.
 *
 * ## Example
 *
 * ```kotlin
 * Stove().with {
 *     httpClient { HttpClientSystemOptions(baseUrl = "https://staging.myapp.com") }
 *     postgresql(AppDb) { PostgresqlOptions.provided(jdbcUrl = "jdbc:...") }
 *     providedApplication {
 *         ProvidedApplicationOptions(
 *             readiness = ReadinessStrategy.HttpGet(
 *                 url = "https://staging.myapp.com/health"
 *             )
 *         )
 *     }
 * }.run()
 * ```
 *
 * @param configure Configuration block for [ProvidedApplicationOptions]. Defaults to no health check.
 * @return [ReadyStove] to chain with `.run()`.
 */
fun WithDsl.providedApplication(
  configure: () -> ProvidedApplicationOptions = { ProvidedApplicationOptions() }
): ReadyStove {
  this.stove.applicationUnderTest(ProvidedApplicationUnderTest(configure()))
  return this.stove
}
