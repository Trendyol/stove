package com.trendyol.stove.testing.e2e.http

import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.*
import org.slf4j.LoggerFactory
import kotlin.time.Duration
import kotlin.time.toJavaDuration

private val httpClientLogger = LoggerFactory.getLogger("com.trendyol.stove.testing.e2e.http.HttpClient")

internal fun jsonHttpClient(
  baseUrl: String,
  timeout: Duration,
  converter: ContentConverter
): HttpClient = HttpClient(OkHttp) {
  engine {
    config {
      followRedirects(true)
      followSslRedirects(true)
      connectTimeout(timeout.toJavaDuration())
      readTimeout(timeout.toJavaDuration())
      callTimeout(timeout.toJavaDuration())
      writeTimeout(timeout.toJavaDuration())
    }
  }

  install(Logging) {
    logger = object : Logger {
      override fun log(message: String) {
        httpClientLogger.info(message)
      }
    }
  }

  install(ContentNegotiation) {
    register(ContentType.Application.Json, converter)
    register(ContentType.Application.ProblemJson, converter)
    register(ContentType.parse("application/x-ndjson"), converter)
  }

  defaultRequest {
    url(baseUrl)
    header(HttpHeaders.ContentType, ContentType.Application.Json)
    header(HttpHeaders.Accept, ContentType.Application.Json)
  }
}
