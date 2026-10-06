package stove.ktor.bff.fixtures

import stove.ktor.bff.StoveConfig
import stove.ktor.bff.auth.*
import stove.ktor.bff.auth.storage.*
import stove.ktor.bff.config.SessionStorageConfiguration
import stove.ktor.oidc.client.TokenBinding
import java.time.Instant
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds

class SessionStores(val first: BrowserSessionStore, val second: BrowserSessionStore) : AutoCloseable {
  fun browser(store: BrowserSessionStore = first, policy: SessionPolicy = SessionPolicy()) = BrowserSessions(
    store,
    policy.copy(revocationPollInterval = 20.milliseconds, refreshPollInterval = 10.milliseconds)
  ) { TokenBinding.Dpop() }

  suspend fun create(policy: SessionPolicy = SessionPolicy(), tokens: SessionTokens = sessionTokens()): SessionPair {
    val one = browser(first, policy)
    val two = browser(second, policy)
    val attempt = one.beginLogin()
    val consumed = two.finishLogin(attempt.state, attempt.state)
    val id = two.createSession(AuthenticatedTokens(VerifiedIdentity("alice", Instant.now().plusSeconds(60)), tokens), consumed)
    return SessionPair(id, one.session(id), two.session(id))
  }

  override fun close() {
    first.close()
    second.close()
  }

  companion object {
    fun inMemory(): SessionStores = InMemoryBrowserSessionStore().let { SessionStores(it, it) }

    suspend fun postgres(namespace: String = UUID.randomUUID().toString()): SessionStores {
      val settings = StoveConfig.postgresSettings + ("BFF_SESSION_NAMESPACE" to namespace)
      val configuration = SessionStorageConfiguration.load(settings)
      val first = configuration.open()
      return try {
        SessionStores(first, configuration.open())
      } catch (failure: Throwable) {
        first.close()
        throw failure
      }
    }
  }
}

data class SessionPair(val id: String, val first: UserSession, val second: UserSession)

fun sessionTokens(access: String = "access-1", refresh: String = "refresh-1", refreshDue: Boolean = true): SessionTokens {
  val now = Instant.now()
  return SessionTokens(access, now.plusSeconds(60), if (refreshDue) now.minusSeconds(1) else now.plusSeconds(50), RefreshToken.Available(refresh))
}
