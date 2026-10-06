package stove.ktor.bff.config

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.postgresql.ds.PGSimpleDataSource
import stove.ktor.bff.auth.storage.*
import stove.ktor.bff.auth.storage.postgres.PostgresBrowserSessionStore

sealed interface SessionStorageConfiguration {
  suspend fun open(): BrowserSessionStore

  data object InMemory : SessionStorageConfiguration {
    override suspend fun open(): BrowserSessionStore = InMemoryBrowserSessionStore()
  }

  data class Postgres(
    val jdbcUrl: String,
    val username: String,
    val password: String,
    val namespace: String = "stove-bff",
    val migrateOnStartup: Boolean = true
  ) : SessionStorageConfiguration {
    init {
      require(jdbcUrl.startsWith("jdbc:postgresql:")) { "BFF_SESSION_JDBC_URL must be a PostgreSQL JDBC URL" }
      require(username.isNotBlank()) { "BFF_SESSION_DB_USER is required" }
      require(namespace.isNotBlank() && namespace.length <= 200) { "Invalid BFF_SESSION_NAMESPACE" }
    }

    override suspend fun open(): BrowserSessionStore = withContext(Dispatchers.IO) {
      val source = PGSimpleDataSource().apply {
        setURL(jdbcUrl)
        user = username
        this.password = this@Postgres.password
        connectTimeout = 5
        socketTimeout = 5
        tcpKeepAlive = true
      }
      val pool = HikariDataSource(
        HikariConfig().apply {
          dataSource = source
          maximumPoolSize = 10
          minimumIdle = 0
          connectionTimeout = 5000
          validationTimeout = 3000
        }
      )
      val store = PostgresBrowserSessionStore(pool, namespace)
      val managed = ManagedSessionStore(store, pool)
      try {
        if (migrateOnStartup) store.migrate()
        managed
      } catch (failure: Throwable) {
        managed.close()
        throw failure
      }
    }
  }

  companion object {
    fun load(settings: Map<String, String>): SessionStorageConfiguration = when (settings.getOrDefault("BFF_SESSION_STORE", "memory")) {
      "memory" -> InMemory

      "postgres" -> Postgres(
        jdbcUrl = settings.getValue("BFF_SESSION_JDBC_URL"),
        username = settings.getValue("BFF_SESSION_DB_USER"),
        password = settings.getValue("BFF_SESSION_DB_PASSWORD"),
        namespace = settings.getOrDefault("BFF_SESSION_NAMESPACE", "stove-bff"),
        migrateOnStartup = settings.getOrDefault("BFF_SESSION_MIGRATE", "true").toBooleanStrict()
      )

      else -> error("BFF_SESSION_STORE must be memory or postgres")
    }
  }
}

private class ManagedSessionStore(
  private val store: BrowserSessionStore,
  private val pool: HikariDataSource
) : BrowserSessionStore by store {
  override fun close() {
    try {
      store.close()
    } finally {
      pool.close()
    }
  }
}
