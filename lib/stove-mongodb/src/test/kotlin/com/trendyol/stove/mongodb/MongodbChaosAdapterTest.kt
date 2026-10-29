package com.trendyol.stove.mongodb

import com.mongodb.ConnectionString
import com.mongodb.MongoClientSettings
import com.mongodb.MongoException
import com.mongodb.MongoSocketException
import com.mongodb.MongoTimeoutException
import com.mongodb.kotlin.client.coroutine.MongoClient
import com.mongodb.kotlin.client.coroutine.MongoDatabase
import com.trendyol.stove.chaos.TcpEndpoint
import com.trendyol.stove.chaos.duringFault
import com.trendyol.stove.system.Stove
import com.trendyol.stove.system.ValidationDsl
import com.trendyol.stove.system.abstractions.SystemKey
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bson.Document
import org.testcontainers.mongodb.MongoDBContainer
import org.testcontainers.utility.DockerImageName
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

class MongodbChaosAdapterTest :
  FunSpec({
    test("routes both URLs and preserves credentials database and options without changing direct configuration") {
      val direct = MongodbExposedConfiguration(
        connectionString = "mongodb://user:p%40ss@localhost:27017/orders?authSource=admin&directConnection=false",
        host = "localhost",
        port = 27017,
        replicaSetUrl = "mongodb://user:p%40ss@localhost:27017/orders?replicaSet=rs0&retryWrites=false"
      )
      val routed = MongodbChaosAdapter.applicationConfiguration(direct, TcpEndpoint("127.0.0.1", 32000))

      routed.connectionString shouldBe
        "mongodb://user:p%40ss@127.0.0.1:32000/orders?authSource=admin&directConnection=true"
      routed.replicaSetUrl shouldBe
        "mongodb://user:p%40ss@127.0.0.1:32000/orders?replicaSet=rs0&retryWrites=false&directConnection=true"
      routed.host shouldBe "127.0.0.1"
      routed.port shouldBe 32000
      ConnectionString(routed.connectionString).isDirectConnection shouldBe true
      direct.host shouldBe "localhost"
      direct.connectionString shouldBe
        "mongodb://user:p%40ss@localhost:27017/orders?authSource=admin&directConnection=false"
    }

    test("routes an IPv6 authority without introducing duplicate direct connection options") {
      val direct = MongodbExposedConfiguration("mongodb://localhost:27017", "localhost", 27017, "mongodb://localhost:27017/?DIRECTCONNECTION=false")
      val routed = MongodbChaosAdapter.applicationConfiguration(direct, TcpEndpoint("::1", 32000))
      routed.connectionString shouldBe "mongodb://[::1]:32000/?directConnection=true"
      routed.replicaSetUrl shouldBe "mongodb://[::1]:32000/?directConnection=true"
    }

    test("rejects discovery load balancing TLS and inconsistent endpoints before registration") {
      val unsupported = listOf(
        "mongodb+srv://cluster.example.com",
        "mongodb://localhost:27017,other:27017",
        "mongodb://localhost:27017/?loadBalanced=true",
        "mongodb://localhost:27017/?tls=true",
        "mongodb://other:27017"
      )
      unsupported.forEach { uri ->
        shouldThrow<IllegalArgumentException> {
          Stove().with {
            mongodb {
              enableChaos()
              MongodbSystemOptions.provided(uri, "localhost", 27017) { emptyList() }
            }
          }
        }
      }
    }

    test("rejects a replica set URL that bypasses the configured endpoint") {
      val direct = MongodbExposedConfiguration("mongodb://localhost:27017", "localhost", 27017, "mongodb://other:27017")
      shouldThrow<IllegalArgumentException> { MongodbChaosAdapter.endpoint(direct) }
    }

    test("a real replica set cannot bypass the keyed application proxy during a partition") {
      withMongoReplicaSet { validation, application ->
        application.runCommand(Document("hello", 1)).getString("setName") shouldBe "docker-rs"
        application.runCommand(Document("ping", 1)).getDouble("ok") shouldBe 1.0

        validation.mongodb(PrimaryMongo) {
          duringFault {
            repeat(3) {
              val failure = shouldThrow<MongoException> { application.runCommand(Document("ping", 1)) }
              (failure is MongoSocketException || failure is MongoTimeoutException) shouldBe true
            }
            mongodb(PrimaryMongo) {
              shouldQuery<Map<String, Any>>("{}", collection = "chaos") { it shouldBe emptyList() }
            }
          }
          eventually(10.seconds) {
            application.runCommand(Document("ping", 1)).getDouble("ok") shouldBe 1.0
          }
        }
      }
    }
  })

private object PrimaryMongo : SystemKey

/** A real replica set, a keyed managed route, and an AUT client consuming its exposed URI. */
private suspend fun withMongoReplicaSet(block: suspend (ValidationDsl, MongoDatabase) -> Unit) {
  MongoDBContainer(DockerImageName.parse("mongo:7.0")).withReplicaSet().use { container ->
    container.start()
    Stove().use { fixture ->
      lateinit var application: MongodbExposedConfiguration
      fixture.with {
        mongodb(PrimaryMongo) {
          enableChaos()
          MongodbSystemOptions.provided(
            connectionString = container.connectionString + "?directConnection=true",
            host = container.host,
            port = container.firstMappedPort,
            replicaSetUrl = container.replicaSetUrl + "?replicaSet=docker-rs"
          ) { configuration ->
            application = configuration
            emptyList()
          }
        }
      }
      fixture.mongodb(PrimaryMongo).run()
      fixture.mongodb(PrimaryMongo).configuration()
      mongoClient(application.replicaSetUrl).use { client ->
        block(ValidationDsl(fixture), client.getDatabase("chaos"))
      }
    }
  }
}

private fun mongoClient(uri: String): MongoClient = MongoClient.create(
  MongoClientSettings.builder()
    .applyConnectionString(ConnectionString(uri))
    .applyToClusterSettings { it.serverSelectionTimeout(1, TimeUnit.SECONDS) }
    .applyToSocketSettings { it.connectTimeout(1, TimeUnit.SECONDS).readTimeout(1, TimeUnit.SECONDS) }
    .build()
)
