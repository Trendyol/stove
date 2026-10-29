package com.trendyol.stove.postgres

import com.trendyol.stove.chaos.applicationConfiguration
import com.trendyol.stove.database.JdbcCloseFailureFixture
import com.trendyol.stove.rdbms.NativeSqlOperations
import com.trendyol.stove.rdbms.RelationalDatabaseChaosAdapter
import com.trendyol.stove.system.Stove
import com.trendyol.stove.system.abstractions.SystemKey
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotliquery.sessionOf

class PostgresqlChaosDslTest : FunSpec({
  test("closes native SQL resources when cleanup fails and preserves both failures") {
    val cleanupFailure = IllegalStateException("cleanup failed")
    val closeFailure = IllegalStateException("connection close failed")
    val jdbc = JdbcCloseFailureFixture(closeFailure)
    val fixture = PostgresqlChaosFixture(cleanupFailure, jdbc)

    shouldThrow<IllegalStateException> { fixture.close() } shouldBe cleanupFailure
    jdbc.closeAttempted shouldBe true
    cleanupFailure.suppressed.toList() shouldBe listOf(closeFailure)
  }

  test("enabling chaos for the primary key preserves the default database configuration") {
    PostgresqlChaosFixture().use { fixture ->
      val original = fixture.direct
      val primary = fixture.primaryApplicationConfiguration()

      primary.host shouldBe "127.0.0.1"
      primary.port shouldNotBe original.port
      primary.jdbcUrl shouldNotBe original.jdbcUrl
      fixture.defaultApplicationConfiguration() shouldBe original
      fixture.direct shouldBe original
    }
  }
})

private object PrimaryDatabase : SystemKey

private class PostgresqlChaosFixture(
  cleanupFailure: RuntimeException? = null,
  jdbc: JdbcCloseFailureFixture? = null
) : AutoCloseable {
  private val stove = Stove()
  private val options = PostgresqlOptions.provided(
    jdbcUrl = "jdbc:postgresql://127.0.0.1:5432/db",
    host = "127.0.0.1",
    port = 5432,
    cleanup = { if (cleanupFailure != null) throw cleanupFailure },
    configureExposedConfiguration = { emptyList() }
  )
  val direct = options.providedConfig

  init {
    stove.with {
      postgresql { options }
      postgresql(PrimaryDatabase) {
        enableChaos()
        options
      }
    }
    if (jdbc != null) stove.postgresql().sqlOperations = NativeSqlOperations(sessionOf(jdbc.dataSource))
  }

  fun defaultApplicationConfiguration() =
    stove.postgresql().applicationConfiguration(direct, RelationalDatabaseChaosAdapter)

  fun primaryApplicationConfiguration() =
    stove.postgresql(PrimaryDatabase).applicationConfiguration(direct, RelationalDatabaseChaosAdapter)

  override fun close() = stove.close()
}
