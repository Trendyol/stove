package com.trendyol.stove.examples.go.e2e.setup

import com.trendyol.stove.system.Stove
import com.trendyol.stove.system.WithDsl
import com.trendyol.stove.system.abstractions.ApplicationUnderTest
import com.trendyol.stove.system.annotations.StoveDsl
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.TimeUnit

private const val HEALTH_CHECK_ATTEMPTS = 30
private const val HEALTH_CHECK_DELAY_MS = 1000L
private const val STOP_TIMEOUT_SECONDS = 5L

/**
 * Custom [ApplicationUnderTest] that starts a Go binary as an OS process.
 *
 * Stove configurations are converted to environment variables via [configMapper],
 * allowing the Go application to read its database connection details from the environment.
 *
 * @param binaryPath Absolute path to the compiled Go binary.
 * @param port The port the Go application will listen on.
 * @param configMapper Converts Stove's `key=value` configuration strings to environment variable maps.
 */
@StoveDsl
class GoApplicationUnderTest(
  private val binaryPath: String,
  private val port: Int,
  private val configMapper: (List<String>) -> Map<String, String>
) : ApplicationUnderTest<Unit> {
  private val logger = LoggerFactory.getLogger(javaClass)
  private var process: Process? = null

  override suspend fun start(configurations: List<String>) {
    val envVars = configMapper(configurations)

    val processBuilder = ProcessBuilder(binaryPath)
      .redirectErrorStream(true)

    processBuilder.environment().putAll(envVars)
    processBuilder.environment()["APP_PORT"] = port.toString()

    process = processBuilder.start()
    launchOutputReader(process!!)
    waitForHealth("http://localhost:$port/health")
  }

  override suspend fun stop() {
    process?.let { p ->
      p.destroy()
      if (!p.waitFor(STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        logger.warn("Go process did not stop gracefully, force-killing")
        p.destroyForcibly()
      }
      logger.info("Go process stopped")
    }
  }

  private fun launchOutputReader(process: Process) {
    Thread {
      process.inputStream.bufferedReader().forEachLine { line ->
        logger.info("[go-app] {}", line)
      }
    }.apply {
      isDaemon = true
      name = "go-app-output-reader"
      start()
    }
  }

  private suspend fun waitForHealth(url: String) {
    val client = HttpClient.newBuilder().build()
    val request = HttpRequest
      .newBuilder()
      .uri(URI.create(url))
      .GET()
      .build()

    repeat(HEALTH_CHECK_ATTEMPTS) { attempt ->
      try {
        val response = client.sendAsync(request, HttpResponse.BodyHandlers.ofString()).await()
        if (response.statusCode() == HTTP_OK) {
          logger.info("Go application is healthy after {} attempts", attempt + 1)
          return
        }
      } catch (
        @Suppress("TooGenericExceptionCaught") e: Exception
      ) {
        logger.debug("Health check attempt {}/{} failed: {}", attempt + 1, HEALTH_CHECK_ATTEMPTS, e.message)
      }
      delay(HEALTH_CHECK_DELAY_MS)
    }
    throw IllegalStateException("Go application failed to become healthy at $url after $HEALTH_CHECK_ATTEMPTS attempts")
  }

  companion object {
    private const val HTTP_OK = 200
  }
}

/**
 * Registers a Go application under test that Stove will start and stop as an OS process.
 *
 * ## Example
 *
 * ```kotlin
 * Stove().with {
 *     httpClient { HttpClientSystemOptions(baseUrl = "http://localhost:8090") }
 *     postgresql { PostgresqlOptions(...) }
 *     goApp(
 *         binaryPath = "/path/to/go-app",
 *         port = 8090,
 *         configMapper = { configs ->
 *             val map = configs.associate { it.split("=", limit = 2).let { (k, v) -> k to v } }
 *             buildMap {
 *                 map["database.host"]?.let { put("DB_HOST", it) }
 *                 map["database.port"]?.let { put("DB_PORT", it) }
 *             }
 *         }
 *     )
 * }.run()
 * ```
 */
fun WithDsl.goApp(
  binaryPath: String = System.getProperty("go.app.binary")
    ?: error("go.app.binary system property not set. Run via: ./gradlew go-recipes:go-showcase:e2eTest"),
  port: Int,
  configMapper: (List<String>) -> Map<String, String>
): Stove {
  this.stove.applicationUnderTest(GoApplicationUnderTest(binaryPath, port, configMapper))
  return this.stove
}
