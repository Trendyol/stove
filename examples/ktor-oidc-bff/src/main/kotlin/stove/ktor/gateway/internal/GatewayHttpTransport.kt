package stove.ktor.gateway.internal

import io.ktor.client.HttpClient
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.coroutineScope
import stove.ktor.gateway.*

internal class GatewayHttpTransport(private val http: HttpClient) {
  suspend fun <T> execute(
    binding: GatewayBinding,
    outgoing: GatewayRequest,
    access: GatewayAccess,
    response: suspend (GatewayResponse) -> T
  ): T {
    val stream = binding.transport as? GatewayTransport.Sse
    val request = http.prepareRequest(binding.target(outgoing.uri)) {
      method = outgoing.method
      headers.appendAll(binding.headers.request(outgoing.headers))
      timeout {
        requestTimeoutMillis = if (stream == null) binding.timeout.inWholeMilliseconds else HttpTimeoutConfig.INFINITE_TIMEOUT_MS
        connectTimeoutMillis = binding.timeout.inWholeMilliseconds
        if (stream != null) socketTimeoutMillis = stream.idleTimeout.inWholeMilliseconds
      }
      if (stream != null) {
        headers[HttpHeaders.Accept] = ContentType.Text.EventStream.toString()
        headers[HttpHeaders.AcceptEncoding] = "identity"
      }
      setBody(outgoing.body.outgoing())
      access.prepare(this)
    }
    if (stream == null) {
      return request.execute { upstream ->
        access.validate(upstream)
        coroutineScope { response(upstream.gatewayResponse(binding)) }
      }
    }
    return opening(binding.timeout) { opened ->
      request.execute { upstream ->
        opened()
        access.validate(upstream)
        coroutineScope { response(upstream.gatewayResponse(binding)) }
      }
    }
  }
}

private suspend fun HttpResponse.gatewayResponse(binding: GatewayBinding): GatewayResponse {
  if (status.value in 300..399 && status != HttpStatusCode.NotModified) {
    throw GatewayFailure(HttpStatusCode.BadGateway, "Upstream redirects are not supported")
  }
  val metadataOnly = request.method == HttpMethod.Head ||
    status in setOf(HttpStatusCode.NotModified, HttpStatusCode.NoContent, HttpStatusCode.ResetContent)
  val events = binding.transport as? GatewayTransport.Sse
  val body = when {
    metadataOnly -> GatewayBody.bytes(byteArrayOf(), binding.responseLimitBytes, HttpStatusCode.BadGateway)

    events != null && status == HttpStatusCode.OK -> {
      requireEventStream(headers)
      GatewayBody.events(bodyAsChannel(), binding.responseLimitBytes, events.idleTimeout)
    }

    else -> GatewayBody.stream(bodyAsChannel(), contentLength(), binding.responseLimitBytes, HttpStatusCode.BadGateway)
  }
  body.checkLength()
  return GatewayResponse(status, binding.headers.response(headers), body, request.method, contentLength())
}

internal fun requireEventStream(headers: Headers) {
  val type = headers[HttpHeaders.ContentType].orEmpty().substringBefore(';').trim()
  if (!type.equals("text/event-stream", ignoreCase = true) || headers[HttpHeaders.ContentEncoding].orEmpty() !in setOf("", "identity")) {
    throw GatewayFailure(HttpStatusCode.BadGateway, "Expected an uncompressed text/event-stream response")
  }
}
