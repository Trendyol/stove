@file:Suppress("TooGenericExceptionThrown", "UseCheckOrError")

package com.trendyol.stove.system

import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import org.slf4j.LoggerFactory
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.time.Duration

/**
 * Executes [ReadinessStrategy] checks to verify that an application is ready
 * before running tests.
 *
 * Used internally by [ProvidedApplicationUnderTest] and `ProcessApplicationUnderTest`.
 *
 * @see ReadinessStrategy
 * @see HealthCheckOptions
 */
object ReadinessChecker {
  private val logger = LoggerFactory.getLogger(ReadinessChecker::class.java)
  private const val TCP_CONNECT_TIMEOUT_MS = 1000

  /**
   * Executes the given [strategy] and blocks until the application is ready
   * or the strategy's retry limit is exhausted.
   *
   * @throws IllegalStateException if readiness cannot be confirmed.
   */
  suspend fun check(strategy: ReadinessStrategy) {
    when (strategy) {
      is ReadinessStrategy.HttpGet -> checkHttp(strategy.options)

      is ReadinessStrategy.TcpPort -> checkTcp(strategy)

      is ReadinessStrategy.Probe -> checkProbe(strategy)

      is ReadinessStrategy.FixedDelay -> {
        logger.info("Waiting ${strategy.delay} for process readiness (fixed delay)")
        delay(strategy.delay)
      }
    }
  }

  /**
   * Performs an HTTP health check using the given [options].
   *
   * Convenience overload for callers that already have [HealthCheckOptions]
   * (e.g., [ProvidedApplicationUnderTest]).
   *
   * @throws IllegalStateException if the health check fails after all retries.
   */
  suspend fun check(options: HealthCheckOptions) {
    checkHttp(options)
  }

  private suspend fun checkHttp(options: HealthCheckOptions) {
    val client = HttpClient.newBuilder()
      .connectTimeout(java.time.Duration.ofMillis(options.timeout.inWholeMilliseconds))
      .build()

    val request = HttpRequest.newBuilder()
      .uri(URI.create(options.url))
      .GET()
      .timeout(java.time.Duration.ofMillis(options.timeout.inWholeMilliseconds))
      .build()

    retryUntilReady(options.retries, options.retryDelay, "Health check failed after ${options.retries} attempts for ${options.url}") {
        attempt,
        total
      ->
      val response = runCatching {
        client.sendAsync(request, HttpResponse.BodyHandlers.ofString()).await()
      }.onFailure {
        logger.warn("Health check attempt ${attempt + 1}/$total failed: ${it.message}")
      }.getOrThrow()

      if (response.statusCode() !in options.expectedStatusCodes) {
        logger.warn("Health check attempt ${attempt + 1}/$total failed: status ${response.statusCode()}")
        throw IllegalStateException("Health check returned unexpected status ${response.statusCode()} from ${options.url}")
      }
      logger.info("Health check passed for ${options.url} (status: ${response.statusCode()})")
    }
  }

  private suspend fun checkTcp(strategy: ReadinessStrategy.TcpPort) {
    retryUntilReady(strategy.retries, strategy.retryDelay, "TCP port ${strategy.port} did not open after ${strategy.retries} attempts") {
        attempt,
        total
      ->
      runCatching {
        Socket().use { socket ->
          socket.connect(InetSocketAddress("localhost", strategy.port), TCP_CONNECT_TIMEOUT_MS)
        }
      }.onFailure {
        logger.debug("TCP check attempt ${attempt + 1}/$total on port ${strategy.port} failed: ${it.message}")
      }.getOrThrow()

      logger.info("TCP port ${strategy.port} is open after ${attempt + 1} attempts")
    }
  }

  private suspend fun checkProbe(strategy: ReadinessStrategy.Probe) {
    retryUntilReady(strategy.retries, strategy.retryDelay, "Readiness probe did not pass after ${strategy.retries} attempts") {
        attempt,
        total
      ->
      val ready = runCatching {
        strategy.check()
      }.onFailure {
        logger.debug("Readiness probe attempt ${attempt + 1}/$total threw: ${it.message}")
      }.getOrThrow()

      if (!ready) {
        logger.debug("Readiness probe attempt ${attempt + 1}/$total returned false")
        error("Probe returned false")
      }
      logger.info("Readiness probe passed after ${attempt + 1} attempts")
    }
  }

  /**
   * Retries [attempt] up to [retries] times with [retryDelay] between attempts.
   * The [attempt] block should return normally on success or throw on failure.
   */
  private suspend fun retryUntilReady(
    retries: Int,
    retryDelay: Duration,
    errorMessage: String,
    attempt: suspend (index: Int, total: Int) -> Unit
  ) {
    var lastException: Throwable? = null
    repeat(retries) { index ->
      runCatching {
        attempt(index, retries)
      }.onSuccess {
        return
      }.onFailure {
        lastException = it
      }
      if (index < retries - 1) {
        delay(retryDelay)
      }
    }
    throw IllegalStateException(errorMessage, lastException as? Exception)
  }
}
