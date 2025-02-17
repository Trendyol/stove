@file:Suppress("ExtractKtorModule")

package stove.ktor.example

import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import org.koin.core.module.Module
import org.koin.dsl.module
import org.koin.ktor.ext.get
import org.koin.ktor.plugin.Koin
import org.koin.logger.SLF4JLogger
import stove.ktor.example.app.*
import stove.ktor.example.application.ExampleAppConsumer

const val CONNECT_TIMEOUT_SECONDS = 10L

fun main(args: Array<String>) {
  run(args, shouldWait = true)
}

fun run(
  args: Array<String>,
  shouldWait: Boolean = false,
  applicationOverrides: () -> Module = { module { } }
): Application {
  val config = loadConfiguration<AppConfiguration>(args)

  val applicationEngine = embeddedServer(Netty, port = config.port, host = "localhost") {
    mainModule(config, applicationOverrides)
  }

  applicationEngine.monitor.subscribe(ApplicationStarted) {
    it.get<ExampleAppConsumer<String, Any>>().start()
  }

  applicationEngine.monitor.subscribe(ApplicationStopping) {
    it.get<ExampleAppConsumer<String, Any>>().stop()
  }

  applicationEngine.start(wait = shouldWait)
  return applicationEngine.application
}

fun Application.mainModule(config: AppConfiguration, applicationOverrides: () -> Module) {
  install(ContentNegotiation) {
    json()
  }

  install(Koin) {
    SLF4JLogger()
    modules(
      module { single { config } },
      kafka(),
      postgresql(),
      app(),
      applicationOverrides()
    )
  }

  configureRouting()
}
