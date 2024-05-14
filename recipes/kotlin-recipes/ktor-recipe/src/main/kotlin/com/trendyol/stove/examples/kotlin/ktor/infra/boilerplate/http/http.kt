package com.trendyol.stove.examples.kotlin.ktor.infra.boilerplate.http

import com.fasterxml.jackson.databind.ObjectMapper
import io.github.oshai.kotlinlogging.*
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.jackson.*
import org.koin.core.KoinApplication
import org.koin.dsl.module

fun KoinApplication.registerHttpClient() {
  modules(module { single { createHttpClient(get()) } })
}

private fun createHttpClient(
  objectMapper: ObjectMapper
): HttpClient = HttpClient(CIO) {
  install(Logging) {
    logger = object : Logger {
      private val logger: KLogger = KotlinLogging.logger("StoveHttpClient")

      override fun log(message: String) {
        logger.info { message }
      }
    }
  }
  install(ContentNegotiation) {
    register(ContentType.Application.Json, JacksonConverter(objectMapper))
  }
  val logger = KotlinLogging.logger("JourneyHttpClient")
  install(HttpRequestRetry) {
    maxRetries = 1
    retryOnServerErrors()
    retryOnException(retryOnTimeout = true)
    exponentialDelay()
    modifyRequest { request ->
      logger.warn(cause) { "Retrying request: ${request.url}" }
      request.headers.append("x-retry-count", retryCount.toString())
    }
  }

  defaultRequest {
    header(HttpHeaders.ContentType, ContentType.Application.Json)
  }
}
