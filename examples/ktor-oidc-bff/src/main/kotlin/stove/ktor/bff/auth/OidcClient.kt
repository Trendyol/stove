package stove.ktor.bff.auth

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.*
import io.ktor.client.request.forms.submitForm
import io.ktor.http.*
import kotlinx.serialization.json.*
import stove.ktor.bff.config.BffConfiguration
import stove.ktor.oidc.client.*
import java.time.Instant

class OidcClient private constructor(
  private val configuration: BffConfiguration,
  private val http: HttpClient,
  private val metadata: JsonObject
) : AutoCloseable {
  private val verifier = IdTokenVerifier(configuration.issuer, configuration.clientId)
  private val tokenEndpoint = metadata.requiredString("token_endpoint")
  private val userInfoEndpoint = metadata.requiredString("userinfo_endpoint")

  fun authorizationUrl(attempt: LoginAttempt, scopes: String = configuration.scopes): String = URLBuilder(
    metadata.requiredString("authorization_endpoint")
  ).apply {
    parameters.appendAll(
      parametersOf(
        "response_type" to listOf("code"),
        "client_id" to listOf(configuration.clientId),
        "redirect_uri" to listOf(configuration.callbackUrl),
        "scope" to listOf(scopes),
        "state" to listOf(attempt.state),
        "nonce" to listOf(attempt.nonce),
        "code_challenge" to listOf(attempt.challenge),
        "code_challenge_method" to listOf("S256")
      )
    )
  }.buildString()

  suspend fun exchange(code: String, attempt: LoginAttempt): AuthenticatedTokens = try {
    val receivedAt = Instant.now()
    val response = tokenRequest(attempt.binding) {
      append("grant_type", "authorization_code")
      append("redirect_uri", configuration.callbackUrl)
      append("code", code)
      append("code_verifier", attempt.verifier)
    }
    val identity = verifier.verify(response.requiredString("id_token"), attempt.nonce, signingKeys())
    AuthenticatedTokens(identity, response.sessionTokens(attempt.binding, receivedAt))
  } catch (error: OidcEndpointRejected) {
    throw LoginRejected("Authorization code exchange rejected", error)
  }

  private suspend fun refresh(session: UserSession, credential: String): SessionTokens = try {
    val receivedAt = Instant.now()
    val response = tokenRequest(session.binding) {
      append("grant_type", "refresh_token")
      append("refresh_token", credential)
    }
    // OIDC permits the refresh response to omit the ID token and its nonce.
    if ("id_token" in response) {
      val identity = verifier.verify(response.requiredString("id_token"), session.nonce, signingKeys(), nonceRequired = false)
      if (identity.subject != session.subject) throw LoginRejected("Refreshed identity changed subject")
    }
    response.sessionTokens(session.binding, receivedAt, RefreshToken.Available(credential))
  } catch (error: OidcEndpointRejected) {
    throw LoginRequired(error)
  } catch (error: LoginRejected) {
    throw LoginRequired(error)
  }

  suspend fun profile(session: UserSession): JsonObject {
    val response = try {
      providerRequest {
        http.get(userInfoEndpoint) { oidcResource(session.binding) { accessToken(session) } }
      }
    } catch (error: OidcEndpointRejected) {
      session.close()
      throw LoginRequired(error)
    }
    if (response.requiredString("sub") != session.subject) {
      session.close()
      throw LoginRequired(LoginRejected("UserInfo subject does not match the session"))
    }
    // Only profile fields cross the browser boundary. OAuth tokens stay in this process.
    return JsonObject(response.filterKeys { it in setOf("sub", "name", "preferred_username", "email") })
  }

  suspend fun accessToken(session: UserSession): String = session.accessToken { refresh(session, it) }

  private suspend fun tokenRequest(binding: TokenBinding, grant: ParametersBuilder.() -> Unit): JsonObject =
    providerRequest {
      http.submitForm(
        tokenEndpoint,
        parameters {
          append("client_id", configuration.clientId)
          append("client_secret", configuration.clientSecret)
          grant()
        }
      ) { oidcToken(binding) }
    }

  private suspend fun signingKeys(): String = providerRequest { http.get(metadata.requiredString("jwks_uri")) }.toString()

  override fun close() = http.close()

  companion object {
    suspend fun connect(configuration: BffConfiguration): OidcClient {
      val http = HttpClient(CIO) {
        install(OidcAuthentication)
        followRedirects = false
        install(HttpTimeout) { requestTimeoutMillis = 5000 }
      }
      return try {
        val metadata = providerRequest { http.get("${configuration.issuer}/.well-known/openid-configuration") }
        require(metadata.requiredString("issuer") == configuration.issuer) { "Discovery issuer does not match OIDC_ISSUER" }
        for (endpoint in listOf("authorization_endpoint", "token_endpoint", "jwks_uri", "userinfo_endpoint")) {
          val url = Url(metadata.requiredString(endpoint))
          require(url.protocol in listOf(URLProtocol.HTTP, URLProtocol.HTTPS)) { "Invalid $endpoint" }
        }
        if (configuration.dpop) {
          val algorithms = metadata["dpop_signing_alg_values_supported"] as? JsonArray ?: JsonArray(emptyList())
          require(JsonPrimitive("ES256") in algorithms) { "Provider must advertise ES256 DPoP support" }
        }
        OidcClient(configuration, http, metadata)
      } catch (error: Exception) {
        http.close()
        throw error
      }
    }
  }
}

internal fun JsonObject.sessionTokens(
  binding: TokenBinding,
  receivedAt: Instant,
  previousRefresh: RefreshToken = RefreshToken.Unavailable
): SessionTokens {
  if (!requiredString("token_type").equals(binding.tokenType, ignoreCase = true)) throw LoginRejected("Unexpected token binding")
  val lifetime = (get("expires_in") as? JsonPrimitive)?.longOrNull ?: throw LoginRejected("Provider omitted access token lifetime")
  if (lifetime !in 1..86400) throw LoginRejected("Invalid access token lifetime")
  val refresh = if ("refresh_token" in this) RefreshToken.Available(requiredString("refresh_token")) else previousRefresh
  return SessionTokens(
    requiredString("access_token"),
    receivedAt.plusSeconds(lifetime),
    receivedAt.plusSeconds(lifetime - minOf(30, lifetime / 10)),
    refresh
  )
}
