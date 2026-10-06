package stove.ktor.gateway

import io.ktor.http.*

/** Explicit boundary between browser credentials, gateway credentials and upstream API headers. */
class GatewayHeaders(request: Set<String>, response: Set<String>) {
  private val request = validate(request)
  private val response = validate(response)

  fun request(source: Headers): Headers = select(source, request)
  fun response(source: Headers): Headers = select(source, response)

  private fun select(source: Headers, allowed: Set<String>): Headers {
    val connectionHeaders = source.getAll(HttpHeaders.Connection).orEmpty().flatMap { it.split(',') }.map { it.trim().lowercase() }.toSet()
    return Headers.build {
      source.forEach { name, values ->
        if (name.lowercase() in allowed && name.lowercase() !in connectionHeaders) appendAll(name, values)
      }
    }
  }

  companion object {
    val DefaultRequest = setOf("Accept", "Accept-Language", "Content-Type", "If-Match", "If-None-Match", "Idempotency-Key")
    val DefaultResponse = setOf("Content-Type", "Content-Language", "Content-Encoding", "ETag", "Last-Modified")
    private val reserved = setOf(
      "authorization", "proxy-authorization", "cookie", "set-cookie", "dpop", "dpop-nonce", "www-authenticate", "proxy-authenticate",
      "host", "origin", "referer", "forwarded", "x-forwarded-for", "x-forwarded-host", "x-forwarded-proto", "x-csrf-token",
      "connection", "keep-alive", "te", "trailer", "transfer-encoding", "upgrade", "content-length", "location", "cache-control",
      "sec-websocket-key", "sec-websocket-version", "sec-websocket-accept", "sec-websocket-protocol", "sec-websocket-extensions"
    )

    private fun validate(names: Set<String>): Set<String> {
      require(
        names.all { name ->
          name.isNotEmpty() && name.all { (it.isLetterOrDigit() && it.code < 128) || it in "!#$%&'*+-.^_`|~" } &&
            name.lowercase() !in reserved
        }
      ) { "Gateway headers must be valid names and cannot override credentials, routing or connection headers" }
      return names.map(String::lowercase).toSet()
    }
  }
}
