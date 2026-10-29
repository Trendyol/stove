package com.trendyol.stove.cassandra

import com.datastax.oss.driver.api.core.CqlSession
import com.datastax.oss.driver.api.core.DriverException
import com.datastax.oss.driver.api.core.config.DefaultDriverOption
import com.datastax.oss.driver.api.core.config.DriverConfigLoader
import com.trendyol.stove.chaos.TcpEndpoint
import com.trendyol.stove.chaos.duringFault
import com.trendyol.stove.system.Stove
import com.trendyol.stove.system.ValidationDsl
import com.trendyol.stove.system.abstractions.SystemKey
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.net.InetSocketAddress
import java.time.Duration
import kotlin.time.Duration.Companion.seconds

class CassandraChaosAdapterTest :
  FunSpec({
    test("only application host and port change for a verified single-node topology") {
      val direct = CassandraExposedConfiguration("localhost", 9042, "datacenter1", "orders")
      val adapter = CassandraChaosAdapter { true }
      adapter.endpoint(direct) shouldBe TcpEndpoint("localhost", 9042)
      adapter.applicationConfiguration(direct, TcpEndpoint("127.0.0.1", 32000)) shouldBe
        CassandraExposedConfiguration("127.0.0.1", 32000, "datacenter1", "orders")
      direct.host shouldBe "localhost"
    }

    test("rejects discovered peers before creating a proxy") {
      shouldThrow<IllegalArgumentException> {
        CassandraChaosAdapter { false }.endpoint(CassandraExposedConfiguration("localhost", 9042, "dc1", "orders"))
      }
    }

    test("rejects provided clusters before registering a runtime") {
      shouldThrow<IllegalArgumentException> {
        Stove().with {
          cassandra {
            enableChaos()
            CassandraSystemOptions.provided(host = "localhost") { emptyList() }
          }
        }
      }
    }

    test("single-node driver connections stay on the keyed proxy through discovery and recovery") {
      withSingleNodeCassandra { validation, application, proxy ->
        application.execute("SELECT release_version FROM system.local").one() shouldNotBe null
        application.metadata.nodes.values.single().endPoint.resolve() shouldBe proxy

        validation.cassandra(PrimaryCassandra) {
          duringFault {
            repeat(3) {
              shouldThrow<DriverException> { application.execute("SELECT release_version FROM system.local") }
            }
            cassandra(PrimaryCassandra) {
              shouldQuery("SELECT release_version FROM system.local") {
                it.one()?.getString("release_version") shouldNotBe null
              }
            }
          }
          eventually(20.seconds) {
            application.execute("SELECT release_version FROM system.local").one() shouldNotBe null
          }
          application.metadata.nodes.values.single().endPoint.resolve() shouldBe proxy
        }
      }
    }
  })

private object PrimaryCassandra : SystemKey

private suspend fun withSingleNodeCassandra(block: suspend (ValidationDsl, CqlSession, InetSocketAddress) -> Unit) {
  Stove().use { fixture ->
    lateinit var application: CassandraExposedConfiguration
    fixture.with {
      cassandra(PrimaryCassandra) {
        enableChaos()
        CassandraSystemOptions { configuration ->
          application = configuration
          emptyList()
        }
      }
    }
    fixture.cassandra(PrimaryCassandra).run()
    fixture.cassandra(PrimaryCassandra).configuration()
    session(application).use { client ->
      block(ValidationDsl(fixture), client, InetSocketAddress(application.host, application.port))
    }
  }
}

private fun session(configuration: CassandraExposedConfiguration): CqlSession = CqlSession.builder()
  .addContactPoint(InetSocketAddress(configuration.host, configuration.port))
  .withLocalDatacenter(configuration.datacenter)
  .withConfigLoader(
    DriverConfigLoader.programmaticBuilder()
      .withDuration(DefaultDriverOption.REQUEST_TIMEOUT, Duration.ofSeconds(2))
      .withDuration(DefaultDriverOption.RECONNECTION_BASE_DELAY, Duration.ofMillis(100))
      .withDuration(DefaultDriverOption.RECONNECTION_MAX_DELAY, Duration.ofSeconds(1))
      .build()
  )
  .build()
