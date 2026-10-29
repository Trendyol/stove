package com.trendyol.stove.mongodb

import com.trendyol.stove.system.Stove
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.testcontainers.utility.DockerImageName

class MongodbShutdownTest : FunSpec({
  test("closing before client initialization stops the container without calling cleanup") {
    Stove().use { stove ->
      var stopped = false
      var cleaned = false
      val container = object : StoveMongoContainer(DockerImageName.parse("mongo:7.0")) {
        override fun stop() {
          stopped = true
        }
      }
      val system = MongodbSystem(
        stove,
        MongodbContext(
          container,
          MongodbSystemOptions(
            cleanup = { cleaned = true },
            configureExposedConfiguration = { emptyList() }
          )
        )
      )

      system.close()

      stopped shouldBe true
      cleaned shouldBe false
    }
  }
})
