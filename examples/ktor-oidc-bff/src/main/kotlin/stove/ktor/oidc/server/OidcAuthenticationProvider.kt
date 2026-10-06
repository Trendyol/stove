package stove.ktor.oidc.server

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import kotlinx.serialization.json.*

/** Ktor resource-server authentication, independent of routes and the issuer's token acquisition flow. */
class OidcAuthenticationProvider(config: Config) : AuthenticationProvider(config) {
  private val verifyAccessToken = config.verifyAccessToken
  private val targetUri = config.targetUri
  private val proofs = config.proofs
  private val requireDpop = config.requireDpop

  class Config(name: String) : AuthenticationProvider.Config(name) {
    lateinit var verifyAccessToken: suspend (String) -> VerifiedAccessToken

    /** Resolve the externally visible URI from trusted configuration, excluding query and fragment. */
    lateinit var targetUri: (ApplicationCall) -> String
    var proofs = DpopVerifier()
    var requireDpop = false
  }

  override suspend fun onAuthenticate(context: AuthenticationContext) {
    try {
      context.principal(name, authenticate(context.call))
    } catch (error: ResourceUnauthorized) {
      val scheme = if (requireDpop ||
        context.call.request.headers[HttpHeaders.Authorization].orEmpty().startsWith("DPoP ", true)
      ) {
        "DPoP"
      } else {
        "Bearer"
      }
      context.challenge("oidc:$name", AuthenticationFailedCause.InvalidCredentials) { challenge, call ->
        call.response.header(HttpHeaders.WWWAuthenticate, "$scheme error=\"${error.error}\"")
        call.oidcProblem(HttpStatusCode.Unauthorized, error.error, error.reason)
        challenge.complete()
      }
    }
  }

  private suspend fun authenticate(call: ApplicationCall): VerifiedAccessToken {
    val authorization = call.request.headers.getAll(HttpHeaders.Authorization)?.singleOrNull().orEmpty().split(' ')
    if (authorization.size != 2 || authorization[1].isBlank()) throw ResourceUnauthorized("invalid_token", "Missing credentials")
    val (scheme, token) = authorization
    val identity = verifyAccessToken(token)
    when (val binding = identity.binding) {
      AccessBinding.Bearer -> {
        if (requireDpop ||
          !scheme.equals("Bearer", ignoreCase = true)
        ) {
          throw ResourceUnauthorized("invalid_token", "Unexpected token binding")
        }
      }

      is AccessBinding.Dpop -> {
        if (!scheme.equals("DPoP", ignoreCase = true)) throw invalidProof("Expected DPoP authorization")
        val proof = call.request.headers.getAll("DPoP")?.singleOrNull() ?: throw invalidProof("Expected exactly one proof")
        proofs.verify(proof, call.request.httpMethod.value, targetUri(call), token, binding.thumbprint)
      }
    }
    return identity
  }
}

fun AuthenticationConfig.oidc(name: String, configure: OidcAuthenticationProvider.Config.() -> Unit) {
  register(OidcAuthenticationProvider(OidcAuthenticationProvider.Config(name).apply(configure)))
}

internal suspend fun ApplicationCall.oidcProblem(status: HttpStatusCode, error: String, description: String = error) {
  response.header(HttpHeaders.CacheControl, "no-store")
  respondText(
    buildJsonObject {
      put("error", error)
      put("error_description", description)
    }.toString(),
    ContentType.Application.Json,
    status
  )
}
