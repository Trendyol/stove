package com.trendyol.stove.system

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.net.ServerSocket
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ReadinessCheckerTest :
  FunSpec({
    context("HttpGet strategy") {
      test("passes when endpoint returns expected status") {
        val port = ServerSocket(0).use { it.localPort }
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress(port), 0)
        server.createContext("/health") { exchange ->
          exchange.sendResponseHeaders(200, 0)
          exchange.responseBody.close()
        }
        server.start()

        try {
          ReadinessChecker.check(
            ReadinessStrategy.HttpGet(
              HealthCheckOptions(
                url = "http://localhost:$port/health",
                retries = 3,
                retryDelay = 100.milliseconds,
                timeout = 2.seconds
              )
            )
          )
        } finally {
          server.stop(0)
        }
      }

      test("fails after retries when endpoint is unreachable") {
        val error = shouldThrow<IllegalStateException> {
          ReadinessChecker.check(
            ReadinessStrategy.HttpGet(
              HealthCheckOptions(
                url = "http://localhost:1/nonexistent",
                retries = 2,
                retryDelay = 50.milliseconds,
                timeout = 500.milliseconds
              )
            )
          )
        }
        error.message shouldContain "Health check failed after 2 attempts"
      }

      test("fails when endpoint returns unexpected status code") {
        val port = ServerSocket(0).use { it.localPort }
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress(port), 0)
        server.createContext("/health") { exchange ->
          exchange.sendResponseHeaders(503, 0)
          exchange.responseBody.close()
        }
        server.start()

        try {
          val error = shouldThrow<IllegalStateException> {
            ReadinessChecker.check(
              ReadinessStrategy.HttpGet(
                HealthCheckOptions(
                  url = "http://localhost:$port/health",
                  retries = 2,
                  retryDelay = 50.milliseconds,
                  timeout = 2.seconds
                )
              )
            )
          }
          error.message shouldContain "Health check failed after 2 attempts"
        } finally {
          server.stop(0)
        }
      }

      test("accepts custom expected status codes") {
        val port = ServerSocket(0).use { it.localPort }
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress(port), 0)
        server.createContext("/health") { exchange ->
          exchange.sendResponseHeaders(204, -1)
          exchange.responseBody.close()
        }
        server.start()

        try {
          ReadinessChecker.check(
            ReadinessStrategy.HttpGet(
              HealthCheckOptions(
                url = "http://localhost:$port/health",
                retries = 2,
                retryDelay = 50.milliseconds,
                timeout = 2.seconds,
                expectedStatusCodes = setOf(204)
              )
            )
          )
        } finally {
          server.stop(0)
        }
      }
    }

    context("TcpPort strategy") {
      test("passes when port is open") {
        val server = ServerSocket(0)
        val port = server.localPort

        try {
          ReadinessChecker.check(
            ReadinessStrategy.TcpPort(
              port = port,
              retries = 3,
              retryDelay = 50.milliseconds
            )
          )
        } finally {
          server.close()
        }
      }

      test("fails after retries when port is closed") {
        // Use port 1 which is almost certainly not open
        val error = shouldThrow<IllegalStateException> {
          ReadinessChecker.check(
            ReadinessStrategy.TcpPort(
              port = 1,
              retries = 2,
              retryDelay = 50.milliseconds
            )
          )
        }
        error.message shouldContain "TCP port 1 did not open after 2 attempts"
      }
    }

    context("Probe strategy") {
      test("passes when probe returns true") {
        ReadinessChecker.check(
          ReadinessStrategy.Probe(retries = 3, retryDelay = 50.milliseconds) { true }
        )
      }

      test("fails after retries when probe returns false") {
        val error = shouldThrow<IllegalStateException> {
          ReadinessChecker.check(
            ReadinessStrategy.Probe(retries = 2, retryDelay = 50.milliseconds) { false }
          )
        }
        error.message shouldContain "Readiness probe did not pass after 2 attempts"
      }

      test("fails after retries when probe throws") {
        val error = shouldThrow<IllegalStateException> {
          ReadinessChecker.check(
            ReadinessStrategy.Probe(retries = 2, retryDelay = 50.milliseconds) {
              error("Connection refused")
            }
          )
        }
        error.message shouldContain "Readiness probe did not pass after 2 attempts"
      }

      test("passes after initial failures") {
        var attempt = 0
        ReadinessChecker.check(
          ReadinessStrategy.Probe(retries = 5, retryDelay = 50.milliseconds) {
            attempt++
            attempt >= 3
          }
        )
        attempt shouldBe 3
      }
    }

    context("FixedDelay strategy") {
      test("completes after specified delay") {
        val start = System.currentTimeMillis()
        ReadinessChecker.check(ReadinessStrategy.FixedDelay(200.milliseconds))
        val elapsed = System.currentTimeMillis() - start
        (elapsed >= 180) shouldBe true
      }
    }

    context("check(HealthCheckOptions) overload") {
      test("delegates to HTTP check") {
        val error = shouldThrow<IllegalStateException> {
          ReadinessChecker.check(
            HealthCheckOptions(
              url = "http://localhost:1/nope",
              retries = 2,
              retryDelay = 50.milliseconds,
              timeout = 500.milliseconds
            )
          )
        }
        error.message shouldContain "Health check failed after 2 attempts"
      }
    }
  })
