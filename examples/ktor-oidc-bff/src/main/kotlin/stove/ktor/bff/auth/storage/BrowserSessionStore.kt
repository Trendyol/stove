package stove.ktor.bff.auth.storage

import stove.ktor.bff.auth.RefreshToken
import stove.ktor.bff.auth.SessionTokens
import stove.ktor.oidc.client.TokenBindingKey
import java.time.Instant
import kotlin.time.Duration

/**
 * Durable authentication state. Implementations must make consume, replace and revoke atomic.
 * Reads and replacements exclude expired records using the store's clock. Replacements never
 * insert missing records: a logout or expired refresh claim cannot be resurrected.
 */
interface BrowserSessionStore : AutoCloseable {
  suspend fun saveLogin(login: StoredLogin, lifetime: Duration)
  suspend fun consumeLogin(state: String): StoredLogin?
  suspend fun createSession(id: String, session: NewSession, lifetime: Duration)
  suspend fun readSession(id: String): SessionSnapshot?
  suspend fun replaceSession(id: String, expectedVersion: Long, session: StoredSession): Boolean
  suspend fun revokeSession(id: String)
  suspend fun removeExpired()
  override fun close() = Unit
}

data class StoredLogin(val state: String, val nonce: String, val verifier: String, val binding: TokenBindingKey)

data class NewSession(
  val subject: String,
  val csrfToken: String,
  val nonce: String,
  val binding: TokenBindingKey,
  val tokens: SessionTokens
) {
  fun expiringAt(deadline: Instant) = StoredSession(subject, csrfToken, nonce, binding, deadline, SessionState.Ready(tokens))
}

data class StoredSession(
  val subject: String,
  val csrfToken: String,
  val nonce: String,
  val binding: TokenBindingKey,
  val expiresAt: Instant,
  val state: SessionState
) {
  val validUntil: Instant get() = when (val current = state) {
    is SessionState.Refreshing -> minOf(expiresAt, current.deadline)

    is SessionState.Ready -> if (current.tokens.refreshToken is RefreshToken.Unavailable) {
      minOf(expiresAt, current.tokens.expiresAt)
    } else {
      expiresAt
    }
  }

  /** A successful refresh must install a token that is still usable at the atomic commit. */
  val replacementValidUntil: Instant get() = when (val current = state) {
    is SessionState.Ready -> minOf(validUntil, current.tokens.expiresAt)
    is SessionState.Refreshing -> validUntil
  }
}

sealed interface SessionState {
  data class Ready(val tokens: SessionTokens) : SessionState

  /** No reusable refresh credential remains in storage while its outcome is uncertain. */
  data class Refreshing(val deadline: Instant) : SessionState
}

data class SessionSnapshot(val session: StoredSession, val version: Long, val observedAt: Instant)
