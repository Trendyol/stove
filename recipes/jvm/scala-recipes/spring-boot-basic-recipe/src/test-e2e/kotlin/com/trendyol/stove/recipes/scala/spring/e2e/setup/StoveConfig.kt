package com.trendyol.stove.recipes.scala.spring.e2e.setup

import com.trendyol.stove.http.*
import com.trendyol.stove.recipes.scala.spring.SpringBootRecipeApp
import com.trendyol.stove.spring.*
import com.trendyol.stove.system.Stove
import io.kotest.core.config.AbstractProjectConfig

class StoveConfig : AbstractProjectConfig() {
  override suspend fun beforeProject() {
    Stove()
      .with {
        httpClient {
          HttpClientSystemOptions(
            baseUrl = "http://localhost:8080"
          )
        }
        bridge()
        springBoot(
          runner = { parameters ->
            SpringBootRecipeApp.run(parameters) {
            }
          },
          withParameters = listOf()
        )
      }.run()
  }

  override suspend fun afterProject() {
    Stove.stop()
  }
}
