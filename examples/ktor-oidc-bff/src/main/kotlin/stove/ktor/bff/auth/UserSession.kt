package stove.ktor.bff.auth

import kotlinx.coroutines.*
import stove.ktor.bff.auth.storage.*
import stove.ktor.oidc.client.TokenBinding

/** A request's session handle. Mutable credentials and refresh ownership live in the store. */
class UserSession internal constructor(
  private val id: String,
  session: StoredSession,
  val binding: TokenBinding,
  private val storage: BrowserSessionStore,
  private val policy: SessionPolicy
) {
  val subject = session.subject
  val csrfToken = session.csrfToken
  val nonce = session.nonce

  suspend fun ensureActive() {
    if (storage.readSession(id) == null) throw LoginRequired()
  }

  /** Polls durable state; missed pub/sub events cannot keep an established connection alive. */
  suspend fun awaitClosed() {
    var snapshot = storage.readSession(id)
    while (snapshot != null) {
      val remaining = java.time.Duration.between(snapshot.observedAt, snapshot.session.validUntil).toMillis() + 1
      delay(minOf(policy.revocationPollInterval.inWholeMilliseconds, remaining.coerceAtLeast(1)))
      snapshot = storage.readSession(id)
    }
  }

  suspend fun accessToken(refresh: suspend (String) -> SessionTokens): String = withTimeout(policy.refreshTimeout * 2) {
    var snapshot = storage.readSession(id)
    while (snapshot != null) {
      when (val state = snapshot.session.state) {
        is SessionState.Ready -> {
          val tokens = state.tokens
          val credential = tokens.refreshToken
          if (tokens.refreshAt.isAfter(snapshot.observedAt) || credential !is RefreshToken.Available) {
            return@withTimeout tokens.accessToken
          }
          val deadline = snapshot.observedAt.plusMillis(policy.refreshTimeout.inWholeMilliseconds)
          val claimed = snapshot.session.copy(state = SessionState.Refreshing(deadline))
          if (storage.replaceSession(id, snapshot.version, claimed)) {
            return@withTimeout refreshOwned(snapshot, credential.value, refresh)
          }
        }

        is SessionState.Refreshing -> delay(policy.refreshPollInterval)
      }
      snapshot = storage.readSession(id)
    }
    throw LoginRequired()
  }

  suspend fun close() = storage.revokeSession(id)

  private suspend fun refreshOwned(snapshot: SessionSnapshot, credential: String, refresh: suspend (String) -> SessionTokens): String {
    try {
      return withTimeout(policy.refreshTimeout) {
        val renewed = refresh(credential)
        val replacement = snapshot.session.copy(state = SessionState.Ready(renewed))
        if (!storage.replaceSession(id, snapshot.version + 1, replacement)) throw LoginRequired()
        renewed.accessToken
      }
    } catch (failure: Throwable) {
      // Failed/cancelled refreshes have an uncertain rotation outcome. A crashed owner instead
      // loses its lease; expiry makes its credential unusable and prevents late completion.
      withContext(NonCancellable) {
        try {
          storage.revokeSession(id)
        } catch (cleanup: Throwable) {
          failure.addSuppressed(cleanup)
        }
      }
      throw failure
    }
  }
}
