package stove.ktor.gateway.internal

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.*
import kotlinx.coroutines.selects.select
import stove.ktor.gateway.GatewayAccess
import stove.ktor.gateway.GatewayFailure
import kotlin.time.Duration

/** Limit only opening the exchange; a healthy stream has no total deadline. */
internal suspend fun <T> opening(timeout: Duration, block: suspend (opened: () -> Unit) -> T): T = supervisorScope {
  val opened = CompletableDeferred<Unit>()
  val exchange = async { block { opened.complete(Unit) } }
  try {
    val ready = withTimeoutOrNull(timeout) {
      select {
        opened.onAwait {}
        exchange.onAwait {}
      }
      true
    }
    if (ready != true) throw GatewayFailure(HttpStatusCode.GatewayTimeout, "Upstream handshake timed out")
    exchange.await()
  } finally {
    exchange.cancel()
  }
}

/** Revocation also interrupts a peer that is idle or blocked by backpressure. */
internal suspend fun <T> GatewayAccess.whileAuthorized(block: suspend () -> T): T = supervisorScope {
  val exchange = async { block() }
  val revoked = async { awaitRevocation() }
  try {
    select {
      exchange.onAwait { it }
      revoked.onAwait { throw GatewayFailure(HttpStatusCode.Unauthorized, "Gateway authorization ended") }
    }
  } finally {
    exchange.cancel()
    revoked.cancel()
  }
}
