package stove.ktor.bff.auth

import stove.ktor.bff.auth.storage.*
import stove.ktor.oidc.client.*
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

class BrowserSessions(
  private val storage: BrowserSessionStore = InMemoryBrowserSessionStore(),
  private val policy: SessionPolicy = SessionPolicy(),
  private val createBinding: () -> TokenBinding = { TokenBinding.Bearer }
) {
  private val bindings = SessionBindings()

  suspend fun beginLogin(): LoginAttempt {
    val attempt = LoginAttempt(randomToken(), randomToken(), randomToken(), createBinding())
    storage.saveLogin(StoredLogin(attempt.state, attempt.nonce, attempt.verifier, attempt.binding.storedKey()), policy.loginLifetime)
    return attempt
  }

  suspend fun finishLogin(state: String, cookie: String): LoginAttempt {
    if (state.isBlank() || !sameToken(state, cookie)) throw LoginRejected("Login state does not match this browser")
    val attempt = storage.consumeLogin(state) ?: throw LoginRejected("Login attempt expired or was already used")
    return LoginAttempt(attempt.state, attempt.nonce, attempt.verifier, attempt.binding.restore())
  }

  suspend fun createSession(authenticated: AuthenticatedTokens, attempt: LoginAttempt): String {
    val id = randomToken()
    val session =
      NewSession(authenticated.identity.subject, randomToken(), attempt.nonce, attempt.binding.storedKey(), authenticated.tokens)
    storage.createSession(id, session, policy.sessionLifetime)
    return id
  }

  suspend fun session(id: String): UserSession {
    val snapshot = storage.readSession(id) ?: throw LoginRequired()
    return UserSession(id, snapshot.session, bindings.get(id, snapshot), storage, policy)
  }

  suspend fun logout(id: String, csrfToken: String) {
    val session = session(id)
    if (!sameToken(session.csrfToken, csrfToken)) throw CsrfRejected()
    session.close()
  }
}

data class LoginAttempt(val state: String, val nonce: String, val verifier: String, val binding: TokenBinding = TokenBinding.Bearer) {
  val challenge: String get() = Base64.getUrlEncoder().withoutPadding().encodeToString(
    MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
  )
}

private val random = SecureRandom()
private fun randomToken(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))
private fun sameToken(first: String, second: String): Boolean =
  MessageDigest.isEqual(first.toByteArray(Charsets.UTF_8), second.toByteArray(Charsets.UTF_8))
