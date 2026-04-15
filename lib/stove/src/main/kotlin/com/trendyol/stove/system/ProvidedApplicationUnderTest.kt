package com.trendyol.stove.system

import com.trendyol.stove.system.abstractions.*
import com.trendyol.stove.system.annotations.StoveDsl
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private const val DEFAULT_HEALTH_CHECK_RETRIES = 10
private const val HTTP_OK = 200

/**
 * Options for health-checking a remote application before running tests.
 *
 * @param url The health check endpoint URL (e.g., "https://staging.myapp.com/actuator/health").
 * @param timeout Maximum time to wait for the health check to succeed.
 * @param retries Number of retry attempts before giving up.
 * @param retryDelay Delay between retry attempts.
 * @param expectedStatusCodes HTTP status codes considered healthy.
 */
data class HealthCheckOptions(
  val url: String,
  val timeout: Duration = 30.seconds,
  val retries: Int = DEFAULT_HEALTH_CHECK_RETRIES,
  val retryDelay: Duration = 1.seconds,
  val expectedStatusCodes: Set<Int> = setOf(HTTP_OK)
) {
  init {
    require(url.isNotBlank()) { "Health check URL must not be blank" }
    require(retries > 0) { "retries must be positive, got $retries" }
    require(timeout.isPositive()) { "timeout must be positive, got $timeout" }
    require(!retryDelay.isNegative()) { "retryDelay must not be negative, got $retryDelay" }
  }
}

/**
 * Options for [ProvidedApplicationUnderTest].
 *
 * @param healthCheck Optional health check configuration. If provided, Stove will verify
 *                    the remote application is reachable before running tests.
 */
data class ProvidedApplicationOptions(
  val healthCheck: HealthCheckOptions? = null
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
 *             healthCheck = HealthCheckOptions(
 *                 url = "https://staging.myapp.com/actuator/health"
 *             )
 *         )
 *     }
 * }.run()
 * ```
 *
 * @see ProvidedApplicationOptions
 * @see HealthCheckOptions
 */
@StoveDsl
class ProvidedApplicationUnderTest(
  private val options: ProvidedApplicationOptions
) : ApplicationUnderTest<Unit> {
  override suspend fun start(configurations: List<String>) {
    options.healthCheck?.let { ReadinessChecker.check(it) }
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
 *             healthCheck = HealthCheckOptions(url = "https://staging.myapp.com/health")
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
