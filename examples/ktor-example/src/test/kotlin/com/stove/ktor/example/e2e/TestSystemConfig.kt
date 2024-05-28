package com.stove.ktor.example.e2e

import com.trendol.stove.testing.e2e.rdbms.postgres.*
import com.trendyol.stove.testing.e2e.*
import com.trendyol.stove.testing.e2e.http.httpClient
import com.trendyol.stove.testing.e2e.standalone.kafka.*
import com.trendyol.stove.testing.e2e.system.*
import com.trendyol.stove.testing.e2e.system.abstractions.*
import com.trendyol.stove.testing.e2e.wiremock.*
import io.kotest.core.config.AbstractProjectConfig
import org.slf4j.*
import stove.ktor.example.app.objectMapperRef
import kotlin.reflect.KClass

class GitlabStateStorageFactory : StateStorageFactory {
  override fun <T : Any> invoke(
    options: TestSystemOptions,
    system: KClass<*>,
    state: KClass<T>
  ): StateStorage<T> = object : StateStorage<T> {
    override suspend fun capture(start: suspend () -> T): T {
      return start()
    }

    override suspend fun recover(otherwise: suspend () -> T): T {
      return otherwise()
    }

    override fun saveStateForNextRun(state: T): T {
      return state
    }

    override fun isSubsequentRun(): Boolean {
      return false
    }
  }
}

class TestSystemConfig : AbstractProjectConfig() {
  private val logger: Logger = LoggerFactory.getLogger("WireMockMonitor")

  override suspend fun beforeProject() =
    TestSystem(baseUrl = "http://localhost:8080") {
//      this.stateStorage(GitlabStateStorageFactory())
      if (this.isRunningLocally()) {
        enableReuseForTestContainers()
        keepDependenciesRunning()
      }
    }.with {
      httpClient()
      bridge()
      postgresql {
        PostgresqlOptions(configureExposedConfiguration = { cfg ->
          listOf(
            "database.jdbcUrl=${cfg.jdbcUrl}",
            "database.host=${cfg.host}",
            "database.port=${cfg.port}",
            "database.name=${cfg.database}",
            "database.username=${cfg.username}",
            "database.password=${cfg.password}"
          )
        })
      }
      kafka {
        stoveKafkaObjectMapperRef = objectMapperRef
        KafkaSystemOptions {
          listOf(
            "kafka.bootstrapServers=${it.bootstrapServers}",
            "kafka.interceptorClasses=${it.interceptorClass}"
          )
        }
      }
      wiremock {
        WireMockSystemOptions(
          port = 9090,
          removeStubAfterRequestMatched = true,
          afterRequest = { e, _ ->
            logger.info(e.request.toString())
          }
        )
      }
      ktor(
        withParameters = listOf(
          "port=8080"
        ),
        runner = { parameters ->
          stove.ktor.example.run(parameters) {
            addTestSystemDependencies()
          }
        }
      )
    }.run()

  override suspend fun afterProject() {
    TestSystem.stop()
  }
}
