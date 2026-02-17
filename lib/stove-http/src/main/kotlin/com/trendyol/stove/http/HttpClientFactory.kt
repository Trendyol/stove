package com.trendyol.stove.http

import com.trendyol.stove.tracing.TraceContext
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.*
import okhttp3.Interceptor
import okhttp3.Response
import org.slf4j.LoggerFactory
import kotlin.time.*

private val httpClientLogger = LoggerFactory.getLogger("com.trendyol.stove.http.HttpClient")

/**
 * OkHttp network interceptor that restores Stove's trace context after the OTel Java Agent
 * has overwritten the `traceparent` header.
 *
 * The OTel agent's OkHttp instrumentation runs at the bytecode level before any interceptors.
 * It creates a new CLIENT span and injects its own `traceparent` header, overwriting the one
 * Stove manually set. This interceptor restores Stove's original value from the backup header
 * [TraceContext.STOVE_TRACEPARENT_BACKUP_HEADER], ensuring the server receives Stove's trace ID.
 *
 * This must be registered as a **network interceptor** (not an application interceptor)
 * so it runs after the OTel agent's modification.
 */
internal class StoveTraceNetworkInterceptor : Interceptor {
  override fun intercept(chain: Interceptor.Chain): Response {
    val request = chain.request()
    val stoveTraceparent = request.header(TraceContext.STOVE_TRACEPARENT_BACKUP_HEADER)
      ?: return chain.proceed(request)

    val restored = request
      .newBuilder()
      .header(TraceContext.TRACEPARENT_HEADER, stoveTraceparent)
      .removeHeader(TraceContext.STOVE_TRACEPARENT_BACKUP_HEADER)
      .build()

    return chain.proceed(restored)
  }
}

@Suppress("MagicNumber")
internal fun jsonHttpClient(
  baseUrl: String,
  timeout: Duration,
  converter: ContentConverter,
  webSocketContentConverter: WebsocketContentConverter,
  pingInterval: Duration,
  configureWebSocket: WebSockets.Config.() -> Unit = {},
  configureClient: HttpClientConfig<*>.() -> Unit = {}
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
    addNetworkInterceptor(StoveTraceNetworkInterceptor())
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

  install(WebSockets) {
    contentConverter = webSocketContentConverter
    this.pingInterval = pingInterval
    configureWebSocket(this)
  }

  defaultRequest {
    url(baseUrl)
    header(HttpHeaders.ContentType, ContentType.Application.Json)
    header(HttpHeaders.Accept, ContentType.Application.Json)
  }

  configureClient(this)
}
