package com.trendyol.stove.postgres

import com.trendyol.stove.database.JdbcCloseFailureFixture
import com.trendyol.stove.rdbms.NativeSqlOperations
import com.trendyol.stove.system.Stove
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotliquery.sessionOf
import org.testcontainers.utility.DockerImageName
import java.io.Closeable

class PostgresqlShutdownTest : FunSpec({
  test("cleanup and connection failures still stop the container without escaping close") {
    ShutdownFixture().use { fixture ->
      fixture.system.close()
      fixture.jdbc.events shouldBe listOf("cleanup", "connection.close", "container.stop")
      fixture.jdbc.cleanupFailure.suppressed.toList() shouldBe listOf(fixture.jdbc.closeFailure)
    }
  }

  test("closing before SQL initialization still stops the container") {
    ShutdownFixture(initialize = false).use { fixture ->
      fixture.system.close()
      fixture.jdbc.events shouldBe listOf("container.stop")
    }
  }
})

private class ShutdownFixture(initialize: Boolean = true) : Closeable {
  val jdbc = JdbcCloseFailureFixture()
  private val stove = Stove()
  private val container = object : StovePostgresqlContainer(DockerImageName.parse("postgres:15-alpine")) {
    override fun stop() = jdbc.containerStopped()
  }
  val system = PostgresqlSystem(
    stove,
    PostgresqlContext(
      container,
      PostgresqlOptions(
        cleanup = { jdbc.cleanup() },
        configureExposedConfiguration = { emptyList() }
      )
    )
  ).apply {
    if (initialize) sqlOperations = NativeSqlOperations(sessionOf(jdbc.dataSource))
  }

  override fun close() = stove.close()
}
