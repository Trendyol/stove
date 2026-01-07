package com.trendyol.stove.examples.kotlin.spring.e2e.setup

import com.trendyol.stove.http.*
import com.trendyol.stove.spring.springBoot
import com.trendyol.stove.system.Stove
import io.kotest.core.config.AbstractProjectConfig

class StoveConfig : AbstractProjectConfig() {
  override suspend fun beforeProject() {
    Stove()
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
    Stove.stop()
  }
}
