package com.trendyol.stove.examples.kotlin.ktor.infra.boilerplate

import com.sksamuel.hoplite.*
import com.sksamuel.hoplite.env.Environment
import com.trendyol.stove.examples.kotlin.ktor.application.RecipeAppConfig
import com.trendyol.stove.recipes.shared.application.BusinessException
import io.github.oshai.kotlinlogging.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import org.slf4j.LoggerFactory

@OptIn(ExperimentalHoplite::class)
inline fun <reified T : Any> loadConfiguration(args: Array<String> = arrayOf()): T = ConfigLoaderBuilder.default()
  .addEnvironmentSource()
  .addCommandLineSource(args)
  .withExplicitSealedTypes()
  .withEnvironment(AppEnv.toEnv())
  .apply {
    when (AppEnv.current()) {
      AppEnv.Local -> {
        addResourceSource("/application.yaml", optional = true)
      }

      AppEnv.Prod -> {
        addResourceSource("/application-prod.yaml", optional = true)
        addResourceSource("/application.yaml", optional = true)
      }

      else -> {
        addResourceSource("/application.yaml", optional = true)
      }
    }
  }
  .build()
  .loadConfigOrThrow<T>()

enum class AppEnv(val env: String) {
  Unspecified(""),
  Local(Environment.local.name),
  Prod(Environment.prod.name)
  ;

  companion object {
    fun current(): AppEnv = when (System.getenv("ENVIRONMENT")) {
      Unspecified.env -> Unspecified
      Local.env -> Local
      Prod.env -> Prod
      else -> Local
    }

    fun toEnv(): Environment = when (current()) {
      Local -> Environment.local
      Prod -> Environment.prod
      else -> Environment.local
    }
  }

  fun isLocal(): Boolean {
    return this === Local
  }

  fun isProd(): Boolean {
    return this === Prod
  }
}

fun startKtorApplication(config: RecipeAppConfig, wait: Boolean = true, configure: Application.() -> Unit): Application {
  val loggerName = configure.javaClass.name.split('$').first()

  val server = embeddedServer(
    Netty,
    environment = applicationEngineEnvironment {
      log = LoggerFactory.getLogger(loggerName)

      module(configure)

      connector {
        port = config.server.port
        host = config.server.host
      }
    }
  )

  return server.start(wait = wait).application
}

fun Application.configureExceptionHandling(logging: KLogger = KotlinLogging.logger {}) {
  install(StatusPages) {
    exception<Throwable> { call, reason ->
      when (reason) {
        is BusinessException -> {
          logging.warn(reason) { "A business exception occurred ${call.request.uri}" }
          call.respond(HttpStatusCode.Conflict, reason.message.toString())
        }

        else -> {
          logging.error(reason) { "An unexpected error occurred ${call.request.uri}" }
          call.respond(HttpStatusCode.InternalServerError, "An unexpected error occurred, please try again later")
        }
      }
    }
  }
}
