package stove.ktor.gateway

import io.ktor.http.*
import io.ktor.http.content.OutgoingContent
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.utils.io.*
import kotlinx.coroutines.coroutineScope

/** Request hooks preserve the incoming stream until they explicitly read or replace it. */
@GatewayDsl
class GatewayRequestBuilder internal constructor(source: Headers, body: GatewayBody, private val limit: Int) {
  val headers = HeadersBuilder().apply { appendAll(source) }
  var body: GatewayBody = body
    private set

  fun bytesBody(value: ByteArray) {
    replace(GatewayBody.bytes(value, limit, HttpStatusCode.PayloadTooLarge))
  }

  fun textBody(value: String, contentType: ContentType = ContentType.Text.Plain) {
    bytesBody(value.toByteArray(Charsets.UTF_8))
    headers[HttpHeaders.ContentType] = contentType.withCharset(Charsets.UTF_8).toString()
  }

  fun streamBody(channel: ByteReadChannel, contentLength: Long? = null) {
    replace(GatewayBody.stream(channel, contentLength, limit, HttpStatusCode.PayloadTooLarge))
  }

  private fun replace(value: GatewayBody) {
    body.cancel()
    body = value
  }
}

internal class GatewayRequest(val uri: String, val method: HttpMethod, val headers: Headers, val body: GatewayBody)

/** Valid inside gatewayRequest's response block. Body reads are explicit and bounded. */
class GatewayResponse internal constructor(
  val status: HttpStatusCode,
  val headers: Headers,
  val body: GatewayBody,
  internal val method: HttpMethod,
  internal val representationLength: Long?
) {
  internal fun content(): OutgoingContent = when {
    status == HttpStatusCode.NoContent -> GatewayMetadata(status, headers, null)

    status == HttpStatusCode.ResetContent -> GatewayMetadata(status, headers, 0)

    method == HttpMethod.Head || status == HttpStatusCode.NotModified -> GatewayMetadata(status, headers, representationLength)

    else -> object : OutgoingContent.WriteChannelContent() {
      override val status = this@GatewayResponse.status
      override val headers = this@GatewayResponse.headers
      override val contentLength = body.contentLength
      override suspend fun writeTo(channel: ByteWriteChannel) = body.writeTo(channel)
    }
  }
}

/** The response block owns the exchange lifetime; returning or throwing releases the upstream connection. */
suspend fun <T> ApplicationCall.gatewayRequest(
  uri: String,
  method: HttpMethod = HttpMethod.Get,
  configure: GatewayRequestBuilder.() -> Unit = {},
  response: suspend (GatewayResponse) -> T
): T = application.attributes[RuntimeKey].request(this, uri, method, configure, response)

/** Stream the response before leaving the gatewayRequest block. */
suspend fun ApplicationCall.respondGateway(response: GatewayResponse) = coroutineScope { respond(response.content()) }

private class GatewayMetadata(
  override val status: HttpStatusCode,
  override val headers: Headers,
  override val contentLength: Long?
) : OutgoingContent.NoContent()
