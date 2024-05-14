package com.trendyol.stove.examples.kotlin.ktor

import com.trendyol.stove.examples.kotlin.ktor.application.RecipeAppConfig
import com.trendyol.stove.examples.kotlin.ktor.infra.boilerplate.*
import com.trendyol.stove.examples.kotlin.ktor.infra.boilerplate.http.registerHttpClient
import com.trendyol.stove.examples.kotlin.ktor.infra.boilerplate.kediatr.registerKediatR
import com.trendyol.stove.examples.kotlin.ktor.infra.boilerplate.mongo.configureMongo
import com.trendyol.stove.examples.kotlin.ktor.infra.boilerplate.serialization.*
import com.trendyol.stove.examples.kotlin.ktor.infra.components.product.api.productApi
import com.trendyol.stove.examples.kotlin.ktor.infra.components.product.registerProductComponents
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.server.application.*
import io.ktor.server.plugins.autohead.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.core.KoinApplication
import org.koin.dsl.module
import org.koin.ktor.plugin.Koin

val logger = KotlinLogging.logger("Stove Ktor Recipe")

object ExampleStoveKtorApp {
  @JvmStatic
  fun main(args: Array<String>) {
    run(args)
  }

  fun run(args: Array<String>, wait: Boolean = true, configure: org.koin.core.module.Module = module { }): Application {
    val config = loadConfiguration<RecipeAppConfig>(args)
    logger.info { "Starting Ktor application with config: $config" }
    return startKtorApplication(config, wait) {
      appModule(config, configure)
    }
  }
}

fun Application.appModule(
  config: RecipeAppConfig,
  overrides: org.koin.core.module.Module = module { }
) {
  install(Koin) {
    allowOverride(true)
    modules(module { single { config } })
    registerAppDeps()
    registerHttpClient()
    modules(overrides)
  }
  configureRouting()
  configureExceptionHandling()
  configureContentNegotiation()
}

fun KoinApplication.registerAppDeps() {
  configureMongo()
  configureJackson()
  registerKediatR()
  registerProductComponents()
}

fun Application.configureRouting() {
  install(AutoHeadResponse)
  routing {
    route("/") {
      get {
        call.respondText("Hello, World!")
      }
    }
    productApi()
  }
}
