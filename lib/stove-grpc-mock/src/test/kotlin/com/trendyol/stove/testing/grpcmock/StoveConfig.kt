package com.trendyol.stove.testing.grpcmock

import com.trendyol.stove.extensions.kotest.StoveKotestExtension
import com.trendyol.stove.grpc.GrpcSystemOptions
import com.trendyol.stove.grpc.grpc
import com.trendyol.stove.system.Stove
import com.trendyol.stove.system.abstractions.ApplicationUnderTest
import io.kotest.core.config.AbstractProjectConfig
import io.kotest.core.extensions.Extension

class StoveConfig : AbstractProjectConfig() {
  override val extensions: List<Extension> = listOf(StoveKotestExtension())

  override suspend fun beforeProject() {
    Stove()
      .with {
        grpcMock {
          GrpcMockSystemOptions(
            port = 9098,
            removeStubAfterRequestMatched = true
          )
        }
        grpc {
          GrpcSystemOptions(
            host = "localhost",
            port = 9098
          )
        }
        applicationUnderTest(
          object : ApplicationUnderTest<Unit> {
            override suspend fun start(configurations: List<String>) = Unit

            override suspend fun stop() = Unit
          }
        )
      }.run()
  }

  override suspend fun afterProject(): Unit = Stove.stop()
}
