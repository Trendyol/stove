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
      is ReadinessStrategy.HttpGet -> checkHttp(strategy)

      is ReadinessStrategy.TcpPort -> checkTcp(strategy)

      is ReadinessStrategy.Probe -> checkProbe(strategy)

      is ReadinessStrategy.FixedDelay -> {
        logger.info("Waiting ${strategy.delay} for process readiness (fixed delay)")
        delay(strategy.delay)
      }
    }
  }

  private suspend fun checkHttp(strategy: ReadinessStrategy.HttpGet) {
    val timeout = java.time.Duration.ofMillis(strategy.timeout.inWholeMilliseconds)
    val client = HttpClient.newBuilder().connectTimeout(timeout).build()
    val request = HttpRequest.newBuilder()
      .uri(URI.create(strategy.url))
      .GET()
      .timeout(timeout)
      .build()

    retryUntilReady(
      strategy.retries,
      strategy.retryDelay,
      "Health check failed after ${strategy.retries} attempts for ${strategy.url}",
      onFailure = { attempt, failure ->
        logger.warn("Health check attempt $attempt/${strategy.retries} failed: ${failure.message}")
      }
    ) {
      val status = client.sendAsync(request, HttpResponse.BodyHandlers.ofString()).await().statusCode()
      check(status in strategy.expectedStatusCodes) {
        "Health check returned unexpected status $status from ${strategy.url}"
      }
      logger.info("Health check passed for ${strategy.url} (status: $status)")
    }
  }

  private suspend fun checkTcp(strategy: ReadinessStrategy.TcpPort) {
    retryUntilReady(
      strategy.retries,
      strategy.retryDelay,
      "TCP port ${strategy.port} did not open after ${strategy.retries} attempts",
      onFailure = { attempt, failure ->
        logger.debug("TCP check attempt $attempt/${strategy.retries} on port ${strategy.port} failed: ${failure.message}")
      }
    ) { attempt ->
      Socket().use { socket ->
        socket.connect(InetSocketAddress("localhost", strategy.port), TCP_CONNECT_TIMEOUT_MS)
      }
      logger.info("TCP port ${strategy.port} is open after $attempt attempts")
    }
  }

  private suspend fun checkProbe(strategy: ReadinessStrategy.Probe) {
    retryUntilReady(
      strategy.retries,
      strategy.retryDelay,
      "Readiness probe did not pass after ${strategy.retries} attempts",
      onFailure = { attempt, failure ->
        logger.debug("Readiness probe attempt $attempt/${strategy.retries} failed: ${failure.message}")
      }
    ) { attempt ->
      check(strategy.check()) { "Probe returned false" }
      logger.info("Readiness probe passed after $attempt attempts")
    }
  }

  /**
   * Retries [attempt] up to [retries] times with [retryDelay] between attempts.
   * The [attempt] block should return normally on success or throw on failure.
   */
  @Suppress("TooGenericExceptionCaught")
  private suspend fun retryUntilReady(
    retries: Int,
    retryDelay: Duration,
    errorMessage: String,
    onFailure: (attempt: Int, failure: Throwable) -> Unit,
    attempt: suspend (number: Int) -> Unit
  ) {
    var lastException: Throwable? = null
    repeat(retries) { index ->
      try {
        attempt(index + 1)
        return
      } catch (failure: Throwable) {
        lastException = failure
        onFailure(index + 1, failure)
      }
      if (index < retries - 1) {
        delay(retryDelay)
      }
    }
    throw IllegalStateException(errorMessage, lastException as? Exception)
  }
}
