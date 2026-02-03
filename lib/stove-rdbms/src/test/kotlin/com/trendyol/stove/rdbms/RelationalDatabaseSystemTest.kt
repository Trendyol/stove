package com.trendyol.stove.rdbms

import com.trendyol.stove.system.Stove
import com.trendyol.stove.system.abstractions.ProvidedRuntime
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.runBlocking
import kotliquery.sessionOf

class RelationalDatabaseSystemTest :
  FunSpec({
    test("run should initialize configuration and sql operations for provided runtime") {
      val stove = Stove()
      val system = TestRelationalDatabaseSystem(stove)

      runBlocking { system.run() }

      system.configuration().joinToString() shouldContain "jdbc:h2:mem:testdb"
      system.internalSqlOperations shouldNotBe null
    }

    test("shouldExecute and shouldQuery should use sql operations") {
      val stove = Stove()
      val system = TestRelationalDatabaseSystem(stove)

      runBlocking {
        system.run()
        system.shouldExecute(
          """
          CREATE TABLE IF NOT EXISTS users (
            id INT PRIMARY KEY,
            name VARCHAR(50)
          )
          """.trimIndent()
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
          users.size shouldBe 1
          users.first().name shouldBe "alice"
        }
      }
    }
  })

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
    jdbcUrl = "jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1",
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
