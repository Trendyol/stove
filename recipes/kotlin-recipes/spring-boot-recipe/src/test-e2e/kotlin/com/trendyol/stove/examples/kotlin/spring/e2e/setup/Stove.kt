package com.trendyol.stove.examples.kotlin.spring.e2e.setup

import com.trendyol.stove.testing.e2e.http.*
import com.trendyol.stove.testing.e2e.springBoot
import com.trendyol.stove.testing.e2e.system.TestSystem
import io.kotest.core.config.AbstractProjectConfig

class Stove : AbstractProjectConfig() {
  override suspend fun beforeProject() {
    TestSystem()
      .with {
        httpClient {
          HttpClientSystemOptions(
            baseUrl = "http://localhost:8024"
          )
        }
        springBoot(
          runner = { params ->
            com.trendyol.stove.examples.kotlin.spring
              .run(params)
          },
          withParameters = listOf(
            "server.port=8024"
          )
        )
      }.run()
  }

  override suspend fun afterProject() {
    TestSystem.stop()
  }
}
