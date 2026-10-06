package stove.ktor.bff.auth

import io.ktor.client.statement.*
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*
import java.io.IOException

/** Translate provider responses; authentication and nonce retries belong to the client plugin. */
internal suspend fun providerRequest(request: suspend () -> HttpResponse): JsonObject = try {
  ProviderResponse.read(request()).content()
} catch (error: IOException) {
  throw ProviderUnavailable("Cannot reach OIDC provider", error)
} catch (error: SerializationException) {
  throw ProviderUnavailable("Provider returned malformed JSON", error)
}

private data class ProviderResponse(val status: Int, val body: JsonObject) {
  private val error: String get() = (body["error"] as? JsonPrimitive)?.content.orEmpty()

  fun content(): JsonObject {
    if (status in 400..499) throw OidcEndpointRejected(status, error)
    if (status !in 200..299) throw ProviderUnavailable("Provider returned HTTP $status")
    return body
  }

  companion object {
    suspend fun read(response: HttpResponse): ProviderResponse {
      val text = response.bodyAsText()
      val body = if (text.isBlank()) {
        JsonObject(emptyMap())
      } else {
        Json.parseToJsonElement(text) as? JsonObject
          ?: throw ProviderUnavailable("Provider returned a non-object response")
      }
      return ProviderResponse(
        response.status.value,
        body
      )
    }
  }
}

internal class OidcEndpointRejected(val status: Int, val error: String) : RuntimeException("Provider returned HTTP $status: $error")

internal fun JsonObject.requiredString(name: String): String {
  val value = get(name)
  if (value !is JsonPrimitive || !value.isString || value.content.isBlank()) {
    throw ProviderUnavailable("Provider omitted or returned invalid $name")
  }
  return value.content
}
