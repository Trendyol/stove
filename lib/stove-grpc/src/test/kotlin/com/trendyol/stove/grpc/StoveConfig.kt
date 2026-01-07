package com.trendyol.stove.grpc

import com.trendyol.stove.extensions.kotest.StoveKotestExtension
import com.trendyol.stove.system.Stove
import com.trendyol.stove.system.abstractions.ApplicationUnderTest
import com.trendyol.stove.system.stove
import io.kotest.core.config.AbstractProjectConfig
import io.kotest.core.extensions.Extension

/**
 * Test application that wraps the [TestGrpcServer].
 * This integrates the gRPC server lifecycle with Stove's test system.
 */
class TestGrpcApp(
  private val port: Int
) : ApplicationUnderTest<TestGrpcServer> {
  private lateinit var server: TestGrpcServer

  override suspend fun start(configurations: List<String>): TestGrpcServer {
    server = TestGrpcServer(port).start()
    return server
  }

  override suspend fun stop() {
    if (::server.isInitialized) {
      server.close()
    }
  }
}

class StoveConfig : AbstractProjectConfig() {
  override val extensions: List<Extension> = listOf(StoveKotestExtension())

  override suspend fun beforeProject() {
    Stove()
      .with {
        grpc {
          GrpcSystemOptions(
            host = TEST_HOST,
            port = TEST_PORT,
            metadata = mapOf("authorization" to "Bearer test-token")
          )
        }

        applicationUnderTest(TestGrpcApp(TEST_PORT))
      }.run()
  }

  override suspend fun afterProject() {
    Stove.stop()
  }

  companion object {
    const val TEST_HOST = "localhost"
    const val TEST_PORT = 50199
  }
}
