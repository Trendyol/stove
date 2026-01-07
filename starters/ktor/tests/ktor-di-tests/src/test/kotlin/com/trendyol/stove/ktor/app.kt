package com.trendyol.stove.ktor

import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.di.*
import org.junit.platform.commons.logging.LoggerFactory
import java.net.ServerSocket

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
