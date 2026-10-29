package com.trendyol.stove.recipes.scala.spring.e2e.setup

import com.trendyol.stove.http.*
import com.trendyol.stove.recipes.scala.spring.SpringBootRecipeApp
import com.trendyol.stove.spring.*
import com.trendyol.stove.system.PortFinder
import com.trendyol.stove.system.Stove
import io.kotest.core.config.AbstractProjectConfig

class StoveConfig : AbstractProjectConfig() {
  private val appPort = PortFinder.findAvailablePort()

  override suspend fun beforeProject() {
    Stove()
      .with {
        httpClient {
          HttpClientSystemOptions(
            baseUrl = "http://localhost:$appPort"
          )
        }
        bridge()
        springBoot(
          runner = { parameters ->
            SpringBootRecipeApp.run(parameters) {
            }
          },
          withParameters = listOf("server.port=$appPort")
        )
      }.run()
  }

  override suspend fun afterProject() {
    Stove.stop()
  }
}
