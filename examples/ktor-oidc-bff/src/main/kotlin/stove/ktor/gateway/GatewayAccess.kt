package stove.ktor.gateway

import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.statement.HttpResponse
import kotlinx.coroutines.awaitCancellation

/** Request-scoped upstream access. The host application owns identity, renewal, and rejection policy. */
interface GatewayAccess {
  fun prepare(request: HttpRequestBuilder) = Unit
  suspend fun validate(response: HttpResponse) = Unit

  /** Return when an established connection must end, for example on logout or session expiry. */
  suspend fun awaitRevocation(): Unit = awaitCancellation()

  data object Anonymous : GatewayAccess
}
