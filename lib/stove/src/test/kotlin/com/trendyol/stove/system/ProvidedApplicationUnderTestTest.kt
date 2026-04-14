package com.trendyol.stove.system

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.runBlocking
import kotlin.time.Duration.Companion.milliseconds

class ProvidedApplicationUnderTestTest :
  FunSpec({
    test("start with no health check is a no-op") {
      val aut = ProvidedApplicationUnderTest(ProvidedApplicationOptions())

      runBlocking { aut.start(listOf("some.config=true")) }
      // No exception — success
    }

    test("stop is a no-op") {
      val aut = ProvidedApplicationUnderTest(ProvidedApplicationOptions())

      runBlocking { aut.stop() }
      // No exception — success
    }

    test("configurations are ignored") {
      val aut = ProvidedApplicationUnderTest(ProvidedApplicationOptions())

      runBlocking {
        aut.start(
          listOf(
            "database.host=localhost",
            "kafka.bootstrap=localhost:9092"
          )
        )
      }
      // No exception — configs silently ignored
    }

    test("health check fails with unreachable URL") {
      val aut = ProvidedApplicationUnderTest(
        ProvidedApplicationOptions(
          healthCheck = HealthCheckOptions(
            url = "http://localhost:1/nonexistent-health",
            retries = 2,
            retryDelay = 50.milliseconds,
            timeout = 500.milliseconds
          )
        )
      )

      val error = shouldThrow<IllegalStateException> {
        runBlocking { aut.start(emptyList()) }
      }

      error.message shouldContain "Health check failed after 2 attempts"
      error.message shouldContain "nonexistent-health"
    }

    test("provided application integrates with Stove lifecycle") {
      val stove = Stove()
      stove.applicationUnderTest(
        ProvidedApplicationUnderTest(ProvidedApplicationOptions())
      )

      runBlocking { stove.run() }

      Stove.instanceInitialized() shouldBe true
    }
  })
