package stove.ktor.gateway

import io.ktor.http.*
import io.ktor.utils.io.ByteReadChannel
import stove.ktor.gateway.internal.requireEventStream

/** Header/status changes leave the body streaming; replacing it discards stale representation metadata. */
@GatewayDsl
class GatewayResponseBuilder internal constructor(private val source: GatewayResponse, private val limit: Int) {
  var status: HttpStatusCode = source.status
  val headers = HeadersBuilder().apply { appendAll(source.headers) }
  var body: GatewayBody = source.body
    private set
  private var replacedBody = false

  fun bytesBody(value: ByteArray) = replace(GatewayBody.bytes(value, limit, HttpStatusCode.BadGateway))

  fun textBody(value: String, contentType: ContentType = ContentType.Text.Plain) {
    bytesBody(value.toByteArray(Charsets.UTF_8))
    headers[HttpHeaders.ContentType] = contentType.withCharset(Charsets.UTF_8).toString()
  }

  fun streamBody(channel: ByteReadChannel, contentLength: Long? = null) =
    replace(GatewayBody.stream(channel, contentLength, limit, HttpStatusCode.BadGateway))

  internal fun build(binding: GatewayBinding): GatewayResponse {
    if (binding.transport is GatewayTransport.WebSocket && source.status == HttpStatusCode.SwitchingProtocols) {
      if (status != HttpStatusCode.SwitchingProtocols || replacedBody) {
        throw GatewayFailure(HttpStatusCode.BadGateway, "WebSocket hooks may change headers or reject, but cannot replace the upgrade")
      }
      return GatewayResponse(status, binding.headers.response(headers.build()), body, source.method, null)
    }
    val unsupportedRedirect = status.value in 300..399 && status != HttpStatusCode.NotModified
    if (status.value !in 200..599 || unsupportedRedirect) {
      throw GatewayFailure(HttpStatusCode.BadGateway, "Unsupported gateway response status")
    }
    body.checkLength()
    if (binding.transport is GatewayTransport.Sse && status == HttpStatusCode.OK) requireEventStream(headers.build())
    val length = if (replacedBody) body.contentLength else source.representationLength
    return GatewayResponse(status, binding.headers.response(headers.build()), body, source.method, length)
  }

  private fun replace(value: GatewayBody) {
    body.cancel()
    body = value
    replacedBody = true
    representationHeaders.forEach(headers::remove)
  }
}

private val representationHeaders = setOf(
  HttpHeaders.ContentEncoding,
  HttpHeaders.ETag,
  HttpHeaders.LastModified,
  HttpHeaders.ContentRange,
  "Content-MD5",
  "Digest",
  "Content-Digest",
  "Repr-Digest"
)
