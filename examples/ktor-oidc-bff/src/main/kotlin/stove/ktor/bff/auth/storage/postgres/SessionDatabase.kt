package stove.ktor.bff.auth.storage.postgres

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import stove.ktor.bff.auth.storage.SessionStorageUnavailable
import java.sql.SQLException
import java.time.Instant
import javax.sql.DataSource

/** JDBC transactions run on IO workers and never span an OIDC request or polling delay. */
internal class SessionDatabase(private val source: DataSource) {
  private val database = Database.connect(source)

  suspend fun <T> query(action: JdbcTransaction.() -> T): T = withContext(Dispatchers.IO) {
    try {
      transaction(database) {
        maxAttempts = 1 // A commit with an uncertain outcome must not replay a consumed login/refresh claim.
        queryTimeout = 5
        action()
      }
    } catch (failure: SQLException) {
      throw SessionStorageUnavailable(failure)
    }
  }

  fun JdbcTransaction.now(): Instant = checkNotNull(
    exec("SELECT clock_timestamp()") { rows ->
      check(rows.next())
      rows.getTimestamp(1).toInstant()
    }
  )

  suspend fun migrate() = withContext(Dispatchers.IO) {
    Flyway.configure()
      .dataSource(source)
      .locations("classpath:db/bff")
      .resourceProvider(SessionMigrations())
      .javaMigrationClassProvider { emptyList() }
      .validateMigrationNaming(true)
      .table("bff_schema_history")
      .baselineOnMigrate(true)
      .baselineVersion("0")
      .load()
      .migrate()
    Unit
  }

  fun close() = TransactionManager.closeAndUnregister(database)
}
