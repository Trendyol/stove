package com.trendyol.stove.ktor

import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.di.*
import org.junit.platform.commons.logging.LoggerFactory
import org.koin.dsl.module
import org.koin.ktor.plugin.Koin
import java.net.ServerSocket
import java.time.Instant

/**
 * Test Ktor application using Ktor-DI for dependency injection.
 */
object KtorDiTestApp {
  private val logger = LoggerFactory.getLogger(KtorDiTestApp::class.java)

  fun run(args: Array<String>): Application {
    logger.info { "Starting Ktor-DI test application with args: ${args.joinToString(" ")}" }
    val port = findAvailablePort()
    val applicationEngine = embeddedServer(Netty, port = port, host = "localhost") {
      dependencies {
        provide<GetUtcNow> { SystemTimeGetUtcNow() }
        provide<ExampleService> { ExampleService(resolve()) }
        provide<TestConfig> { TestConfig() }

        // Multiple payment service implementations as List<PaymentService>
        provide<List<PaymentService>> {
          listOf(
            StripePaymentService(),
            PayPalPaymentService(),
            SquarePaymentService()
          )
        }
      }
    }
    applicationEngine.start(wait = false)
    return applicationEngine.application
  }

  private fun findAvailablePort(): Int = ServerSocket(0).use { it.localPort }
}

/**
 * Test app with both Ktor-DI and Koin active.
 * Koin intentionally provides conflicting values to verify Ktor-DI precedence.
 */
object BothActiveDiTestApp {
  private val logger = LoggerFactory.getLogger(BothActiveDiTestApp::class.java)

  fun run(args: Array<String>): Application {
    logger.info { "Starting both-active DI test application with args: ${args.joinToString(" ")}" }
    val port = findAvailablePort()
    val applicationEngine = embeddedServer(Netty, port = port, host = "localhost") {
      install(Koin) {
        modules(
          module {
            single<GetUtcNow> { GetUtcNow { Instant.parse("2030-01-01T00:00:00Z") } }
            single { ExampleService(get()) }
            single { TestConfig(message = "from-koin") }
            single<List<PaymentService>> { listOf(StripePaymentService()) }
          }
        )
      }
      dependencies {
        provide<GetUtcNow> { SystemTimeGetUtcNow() }
        provide<ExampleService> { ExampleService(resolve()) }
        provide<TestConfig> { TestConfig() }

        // Multiple payment service implementations as List<PaymentService>
        provide<List<PaymentService>> {
          listOf(
            StripePaymentService(),
            PayPalPaymentService(),
            SquarePaymentService()
          )
        }
      }
    }
    applicationEngine.start(wait = false)
    return applicationEngine.application
  }

  private fun findAvailablePort(): Int = ServerSocket(0).use { it.localPort }
}

/**
 * Test app with no DI framework installed in runtime.
 */
object NoDiTestApp {
  private val logger = LoggerFactory.getLogger(NoDiTestApp::class.java)

  fun run(args: Array<String>): Application {
    logger.info { "Starting no-DI test application with args: ${args.joinToString(" ")}" }
    val port = findAvailablePort()
    val applicationEngine = embeddedServer(Netty, port = port, host = "localhost") {}
    applicationEngine.start(wait = false)
    return applicationEngine.application
  }

  private fun findAvailablePort(): Int = ServerSocket(0).use { it.localPort }
}
