package com.trendyol.stove.oidc

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.url
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.readBuffer
import kotlinx.coroutines.withTimeout
import kotlinx.io.readByteArray
import java.net.URI
import java.net.URISyntaxException
import kotlin.time.Duration

/** Owns endpoint routing, request deadlines and bounded response consumption. */
internal class OidcHttpTransport(private val options: OidcSystemOptions) : AutoCloseable {
  private val client = HttpClient(CIO) {
    options.configureHttpClient(this)
    // OAuth codes are single-use, and credentials must never follow redirects.
    followRedirects = false
    expectSuccess = false
    install(HttpRequestRetry) { noRetry() }
    install(HttpTimeout) {
      connectTimeoutMillis = options.requestTimeout.inWholeMilliseconds.coerceAtLeast(1)
      requestTimeoutMillis = options.requestTimeout.inWholeMilliseconds.coerceAtLeast(1)
    }
  }

  fun request(endpoint: String, timeout: Duration = options.requestTimeout): HttpRequestBuilder = HttpRequestBuilder().apply {
    url(httpUri(options.resolveEndpoint(endpoint)).toString())
    timeout { requestTimeoutMillis = timeout.inWholeMilliseconds.coerceAtLeast(1) }
  }

  suspend fun send(request: HttpRequestBuilder): OidcHttpResponse = withTimeout(options.requestTimeout) {
    client.prepareRequest(request).execute { response ->
      val bytes = response.bodyAsChannel().readBuffer((MAX_RESPONSE_BYTES + 1).toLong()).readByteArray()
      check(bytes.size <= MAX_RESPONSE_BYTES) { "OIDC response too large" }
      OidcHttpResponse(response.status.value, bytes)
    }
  }

  override fun close() = client.close()

  companion object {
    private const val MAX_RESPONSE_BYTES = 1024 * 1024
  }
}

internal class OidcHttpResponse(val status: Int, val body: ByteArray)

internal fun httpUri(value: String): URI {
  val uri = try {
    URI(value)
  } catch (error: URISyntaxException) {
    throw OidcConfigurationException("endpoint", "Endpoint must be a valid HTTP(S) URL: $value", error)
  }
  requireConfiguration(
    uri.scheme in setOf("http", "https") && uri.host != null && uri.rawUserInfo == null && uri.rawFragment == null,
    "endpoint",
    "Endpoints must be HTTP(S) URLs without user information or fragments: $value"
  )
  return uri
}
