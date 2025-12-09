package com.trendyol.stove.testing.e2e.ktor

import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import org.junit.platform.commons.logging.LoggerFactory
import org.koin.dsl.module
import org.koin.ktor.plugin.Koin
import java.net.ServerSocket

/**
 * Test Ktor application using Koin for dependency injection.
 */
object KoinTestApp {
  private val logger = LoggerFactory.getLogger(KoinTestApp::class.java)

  fun run(args: Array<String>): Application {
    logger.info { "Starting Koin test application with args: ${args.joinToString(" ")}" }
    val port = findAvailablePort()
    val applicationEngine = embeddedServer(Netty, port = port, host = "localhost") {
      install(Koin) {
        modules(
          module {
            single<GetUtcNow> { SystemTimeGetUtcNow() }
            single { ExampleService(get()) }
            single { TestConfig() }

            // Multiple payment service implementations as List<PaymentService>
            single<List<PaymentService>> {
              listOf(
                StripePaymentService(),
                PayPalPaymentService(),
                SquarePaymentService()
              )
            }
          }
        )
      }
    }
    applicationEngine.start(wait = false)
    return applicationEngine.application
  }

  private fun findAvailablePort(): Int = ServerSocket(0).use { it.localPort }
}
