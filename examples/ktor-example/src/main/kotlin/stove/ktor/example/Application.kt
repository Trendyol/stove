@file:Suppress("ExtractKtorModule")

package stove.ktor.example

import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.callloging.*
import io.ktor.server.plugins.contentnegotiation.*
import org.koin.core.module.Module
import org.koin.dsl.module
import org.koin.ktor.plugin.Koin
import org.koin.logger.SLF4JLogger
import stove.ktor.example.app.*

const val CONNECT_TIMEOUT_SECONDS = 10L

fun main(args: Array<String>) {
  run(args, shouldWait = true)
}

fun run(
  args: Array<String>,
  shouldWait: Boolean = false,
  applicationOverrides: () -> Module = { module { } }
): Application {
  val applicationEngine = embeddedServer(Netty, port = 8080, host = "0.0.0.0") {
    mainModule(args, applicationOverrides)
  }
  applicationEngine.start(wait = shouldWait)
  return applicationEngine.application
}

fun Application.mainModule(args: Array<String>, applicationOverrides: () -> Module) {
  install(ContentNegotiation) {
    json()
  }

  install(Koin) {
    SLF4JLogger()
    modules(
      postgresql(args),
      app(),
      applicationOverrides()
    )
  }

  configureRouting()
}
