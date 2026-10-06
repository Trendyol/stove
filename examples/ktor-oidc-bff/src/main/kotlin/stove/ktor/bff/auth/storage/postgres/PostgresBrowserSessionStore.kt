package stove.ktor.bff.auth.storage.postgres

import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import stove.ktor.bff.auth.storage.*
import javax.sql.DataSource
import kotlin.time.Duration

/** Shared state using Exposed compare-and-set updates and atomic DELETE RETURNING. Owns no data source. */
class PostgresBrowserSessionStore(dataSource: DataSource, namespace: String = "stove-bff") : BrowserSessionStore {
  private val database = SessionDatabase(dataSource)
  private val storeNamespace = namespace

  init {
    require(namespace.isNotBlank() && namespace.length <= 200)
  }

  /** Flyway serializes concurrent pod startup and validates previously applied migrations. */
  suspend fun migrate() = database.migrate()

  override fun close() = database.close()

  override suspend fun saveLogin(login: StoredLogin, lifetime: Duration) {
    database.query {
      val deadline = with(database) { now() }.plusMillis(lifetime.inWholeMilliseconds)
      LoginAttempts.insert {
        it[namespace] = storeNamespace
        it[id] = login.state
        it[payload] = LoginDocument.from(login)
        it[expiresAt] = deadline
      }
    }
  }

  override suspend fun consumeLogin(state: String): StoredLogin? = database.query {
    val row = LoginAttempts.deleteReturning(returning = LoginAttempts.columns + StoreNow) {
      (LoginAttempts.namespace eq storeNamespace) and (LoginAttempts.id eq state)
    }.singleOrNull() ?: return@query null
    row[LoginAttempts.payload].takeIf { row[LoginAttempts.expiresAt].isAfter(row[StoreNow]) }?.toLogin()
  }

  override suspend fun createSession(id: String, session: NewSession, lifetime: Duration) {
    database.query {
      val record = session.expiringAt(with(database) { now() }.plusMillis(lifetime.inWholeMilliseconds))
      Sessions.insert {
        it[namespace] = storeNamespace
        it[Sessions.id] = id
        it[version] = 0
        it[payload] = SessionDocument.from(record)
        it[expiresAt] = record.validUntil
      }
    }
  }

  override suspend fun readSession(id: String): SessionSnapshot? = database.query {
    val row = Sessions.select(Sessions.columns + StoreNow).where {
      (Sessions.namespace eq storeNamespace) and (Sessions.id eq id) and (Sessions.expiresAt greater StoreNow)
    }.singleOrNull() ?: return@query null
    val now = row[StoreNow]
    SessionSnapshot(row[Sessions.payload].toSession(), row[Sessions.version], now).takeIf { it.session.validUntil.isAfter(now) }
  }

  override suspend fun replaceSession(id: String, expectedVersion: Long, session: StoredSession): Boolean = database.query {
    Sessions.update({
      (Sessions.namespace eq storeNamespace) and (Sessions.id eq id) and (Sessions.version eq expectedVersion) and
        (Sessions.expiresAt greater StoreNow) and (StoreNow less session.replacementValidUntil)
    }) {
      it[version] = expectedVersion + 1
      it[payload] = SessionDocument.from(session)
      it[expiresAt] = session.validUntil
    } == 1
  }

  override suspend fun revokeSession(id: String) {
    val sessionId = id
    database.query { Sessions.deleteWhere { (Sessions.namespace eq storeNamespace) and (Sessions.id eq sessionId) } }
  }

  override suspend fun removeExpired() {
    database.query {
      LoginAttempts.deleteWhere { (LoginAttempts.namespace eq storeNamespace) and (expiresAt lessEq StoreNow) }
      Sessions.deleteWhere { (Sessions.namespace eq storeNamespace) and (expiresAt lessEq StoreNow) }
    }
  }
}
