package stove.ktor.bff.gateway

import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import kotlinx.coroutines.awaitCancellation
import stove.ktor.gateway.*
import stove.ktor.oidc.client.*

/** The BFF adapter is the only place where gateway transport meets OIDC and browser sessions. */
fun GatewayConfig.oidc(session: (ApplicationCall) -> OidcGatewayAccess) {
  client { install(OidcAuthentication) }
  access = session
}

class OidcGatewayAccess(
  private val binding: TokenBinding,
  private val accessToken: suspend () -> String,
  private val invalidate: suspend () -> Unit,
  private val revoked: suspend () -> Unit = { awaitCancellation() }
) : GatewayAccess {
  override fun prepare(request: HttpRequestBuilder) = request.oidcResource(binding, accessToken)

  override suspend fun validate(response: HttpResponse) {
    if (response.status != HttpStatusCode.Unauthorized) return
    invalidate()
    throw GatewayFailure(HttpStatusCode.Unauthorized, "Please sign in")
  }

  override suspend fun awaitRevocation() = revoked()
}
