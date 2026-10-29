package com.trendyol.stove.rdbms

import com.trendyol.stove.system.Stove
import com.trendyol.stove.system.abstractions.ProvidedRuntime
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import kotliquery.sessionOf
import java.io.Closeable
import java.util.UUID

class RelationalDatabaseSystemTest :
  FunSpec({
    test("run initializes configuration and SQL operations for provided runtime") {
      RelationalDatabaseFixture().use { fixture ->
        fixture.system.run()

        fixture.system.configuration().joinToString() shouldContain "jdbc:h2:mem:"
        fixture.system.internalSqlOperations shouldNotBe null
      }
    }

    test("SQL DSL creates and reads a user") {
      RelationalDatabaseFixture().use { fixture ->
        val system = fixture.system
        system.run()
        system.shouldExecute(
          "CREATE TABLE users (id INT PRIMARY KEY, name VARCHAR(50))"
        )
        system.shouldExecute("INSERT INTO users (id, name) VALUES (1, 'alice')")

        data class User(
          val id: Int,
          val name: String
        )

        system.shouldQuery<User>(
          query = "SELECT * FROM users",
          mapper = { row -> User(row.int("id"), row.string("name")) }
        ) { users ->
          users shouldBe listOf(User(1, "alice"))
        }
      }
    }

    test("closing before SQL initialization is safe") {
      RelationalDatabaseFixture().use { fixture ->
        fixture.system.close()
      }
    }
  })

private class RelationalDatabaseFixture : Closeable {
  private val stove = Stove()
  val system = TestRelationalDatabaseSystem(stove)

  override fun close() {
    try {
      system.close()
    } finally {
      stove.close()
    }
  }
}

private class TestRelationalDatabaseSystem(
  stove: Stove
) : RelationalDatabaseSystem<TestRelationalDatabaseSystem>(
  stove = stove,
  context = object : RelationalDatabaseContext(
    runtime = ProvidedRuntime,
    configureExposedConfiguration = { cfg ->
      listOf("jdbcUrl=${cfg.jdbcUrl}")
    }
  ) {}
) {
  private val config = RelationalDatabaseExposedConfiguration(
    jdbcUrl = "jdbc:h2:mem:${UUID.randomUUID()}",
    host = "localhost",
    port = 0,
    username = "sa",
    password = ""
  )

  override fun database(exposedConfiguration: RelationalDatabaseExposedConfiguration) = sessionOf(
    url = exposedConfiguration.jdbcUrl,
    user = exposedConfiguration.username,
    password = exposedConfiguration.password
  )

  override fun getProvidedConfig(): RelationalDatabaseExposedConfiguration = config
}
