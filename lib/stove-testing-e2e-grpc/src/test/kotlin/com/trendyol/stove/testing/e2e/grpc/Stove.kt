package com.trendyol.stove.testing.e2e.grpc

import com.trendyol.stove.testing.e2e.system.TestSystem
import com.trendyol.stove.testing.e2e.system.abstractions.ApplicationUnderTest
import io.kotest.core.config.AbstractProjectConfig

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

class Stove : AbstractProjectConfig() {
  override suspend fun beforeProject() {
    TestSystem()
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
    TestSystem.stop()
  }

  companion object {
    const val TEST_HOST = "localhost"
    const val TEST_PORT = 50199
  }
}
