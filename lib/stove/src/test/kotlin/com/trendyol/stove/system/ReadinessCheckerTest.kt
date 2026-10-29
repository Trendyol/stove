package com.trendyol.stove.system

import com.sun.net.httpserver.HttpServer
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.http.HttpTimeoutException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

class ReadinessCheckerTest : FunSpec({
  context("HTTP readiness") {
    test("waits for a recovering application and stops checking once healthy") {
      HealthEndpoint(503, 503, 200).use { endpoint ->
        ReadinessChecker.check(endpoint.strategy(retries = 5))

        endpoint.requests shouldBe 3
      }
    }

    test("reports the last unhealthy response after exhausting retries") {
      HealthEndpoint(503).use { endpoint ->
        val failure = shouldThrow<IllegalStateException> {
          ReadinessChecker.check(endpoint.strategy(retries = 2))
        }

        endpoint.requests shouldBe 2
        failure.message shouldContain "Health check failed after 2 attempts"
        failure.cause?.message shouldContain "unexpected status 503"
      }
    }

    test("accepts a custom healthy status immediately") {
      HealthEndpoint(204).use { endpoint ->
        ReadinessChecker.check(endpoint.strategy(expectedStatusCodes = setOf(204)))

        endpoint.requests shouldBe 1
      }
    }

    test("reports a timeout when the endpoint accepts connections but never responds") {
      ServerSocket(0).use { unresponsive ->
        val failure = shouldThrow<IllegalStateException> {
          ReadinessChecker.check(
            ReadinessStrategy.HttpGet(
              url = "http://localhost:${unresponsive.localPort}/health",
              retries = 2,
              retryDelay = 1.milliseconds,
              timeout = 100.milliseconds
            )
          )
        }

        failure.message shouldContain "Health check failed after 2 attempts"
        failure.cause.shouldBeInstanceOf<HttpTimeoutException>()
      }
    }
  }

  context("TCP readiness") {
    test("passes when the application port is open") {
      ServerSocket(0).use { server ->
        ReadinessChecker.check(ReadinessStrategy.TcpPort(server.localPort))
      }
    }

    test("fails after retries when the port is closed") {
      val port = ServerSocket(0).use { it.localPort }
      val failure = shouldThrow<IllegalStateException> {
        ReadinessChecker.check(ReadinessStrategy.TcpPort(port, retries = 2, retryDelay = 1.milliseconds))
      }

      failure.message shouldContain "TCP port $port did not open after 2 attempts"
    }
  }

  context("custom readiness probes") {
    test("stops retrying as soon as the application becomes ready") {
      var attempts = 0
      ReadinessChecker.check(
        ReadinessStrategy.Probe(retries = 5, retryDelay = 1.milliseconds) {
          ++attempts == 3
        }
      )

      attempts shouldBe 3
    }

    test("a throwing probe can recover on a later attempt") {
      var attempts = 0
      ReadinessChecker.check(
        ReadinessStrategy.Probe(retries = 3, retryDelay = 1.milliseconds) {
          if (++attempts == 1) error("application still starting")
          true
        }
      )

      attempts shouldBe 2
    }

    test("an unhealthy probe exhausts its retry budget") {
      var attempts = 0
      val failure = shouldThrow<IllegalStateException> {
        ReadinessChecker.check(
          ReadinessStrategy.Probe(retries = 2, retryDelay = 1.milliseconds) {
            attempts++
            false
          }
        )
      }

      attempts shouldBe 2
      failure.message shouldContain "Readiness probe did not pass after 2 attempts"
      failure.cause?.message shouldBe "Probe returned false"
    }

    test("a failing probe retains the final error as its cause") {
      var attempts = 0
      val failure = shouldThrow<IllegalStateException> {
        ReadinessChecker.check(
          ReadinessStrategy.Probe(retries = 2, retryDelay = 1.milliseconds) {
            error("attempt ${++attempts}")
          }
        )
      }

      attempts shouldBe 2
      failure.cause?.message shouldBe "attempt 2"
    }
  }

  test("fixed delay waits before allowing application validation") {
    val start = TimeSource.Monotonic.markNow()

    ReadinessChecker.check(ReadinessStrategy.FixedDelay(25.milliseconds))

    (start.elapsedNow() >= 25.milliseconds) shouldBe true
  }
})

private class HealthEndpoint(vararg statuses: Int) : AutoCloseable {
  private val requestCount = AtomicInteger()
  private val server = HttpServer.create(InetSocketAddress("localhost", 0), 0).apply {
    createContext("/health") { exchange ->
      val index = requestCount.getAndIncrement().coerceAtMost(statuses.lastIndex)
      exchange.sendResponseHeaders(statuses[index], -1)
      exchange.close()
    }
    start()
  }
  val requests: Int get() = requestCount.get()

  fun strategy(retries: Int = 3, expectedStatusCodes: Set<Int> = setOf(200)): ReadinessStrategy.HttpGet =
    ReadinessStrategy.HttpGet(
      url = "http://localhost:${server.address.port}/health",
      retries = retries,
      retryDelay = 1.milliseconds,
      timeout = 2.seconds,
      expectedStatusCodes = expectedStatusCodes
    )

  override fun close() = server.stop(0)
}
