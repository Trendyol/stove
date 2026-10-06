package stove.ktor.bff.auth.storage

import java.time.Clock
import java.time.Instant
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.time.Duration

/** One process only; uses exactly the same version/expiry contract as the PostgreSQL adapter. */
class InMemoryBrowserSessionStore(private val clock: Clock = Clock.systemUTC()) : BrowserSessionStore {
  private data class Login(val value: StoredLogin, val expiresAt: Instant)
  private data class Session(val value: StoredSession, val version: Long)
  private val lock = ReentrantLock()
  private val logins = mutableMapOf<String, Login>()
  private val sessions = mutableMapOf<String, Session>()

  override suspend fun saveLogin(login: StoredLogin, lifetime: Duration) = lock.withLock {
    purge()
    check(login.state !in logins) { "Login already exists" }
    logins[login.state] = Login(login, clock.instant().plusMillis(lifetime.inWholeMilliseconds))
  }

  override suspend fun consumeLogin(state: String): StoredLogin? = lock.withLock {
    logins.remove(state)?.takeIf { it.expiresAt.isAfter(clock.instant()) }?.value
  }

  override suspend fun createSession(id: String, session: NewSession, lifetime: Duration) = lock.withLock {
    purge()
    check(id !in sessions) { "Session already exists" }
    sessions[id] = Session(session.expiringAt(clock.instant().plusMillis(lifetime.inWholeMilliseconds)), 0)
  }

  override suspend fun readSession(id: String): SessionSnapshot? = lock.withLock {
    val now = clock.instant()
    val entry = sessions[id] ?: return@withLock null
    if (!entry.value.validUntil.isAfter(now)) {
      sessions.remove(id)
      return@withLock null
    }
    SessionSnapshot(entry.value, entry.version, now)
  }

  override suspend fun replaceSession(id: String, expectedVersion: Long, session: StoredSession): Boolean = lock.withLock {
    val current = sessions[id] ?: return@withLock false
    val now = clock.instant()
    if (current.version != expectedVersion || !current.value.validUntil.isAfter(now) ||
      !session.replacementValidUntil.isAfter(now)
    ) {
      return@withLock false
    }
    sessions[id] = Session(session, expectedVersion + 1)
    true
  }

  override suspend fun revokeSession(id: String) {
    lock.withLock { sessions.remove(id) }
  }

  override suspend fun removeExpired() = lock.withLock { purge() }

  private fun purge() {
    val now = clock.instant()
    logins.entries.removeIf { !it.value.expiresAt.isAfter(now) }
    sessions.entries.removeIf { !it.value.value.validUntil.isAfter(now) }
  }
}
