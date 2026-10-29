package com.trendyol.stove.couchbase

import com.trendyol.stove.system.Stove
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.testcontainers.couchbase.BucketDefinition
import org.testcontainers.utility.DockerImageName

class CouchbaseShutdownTest : FunSpec({
  test("closing before cluster initialization stops the container without calling cleanup") {
    Stove().use { stove ->
      var stopped = false
      var cleaned = false
      val container = object : StoveCouchbaseContainer(DockerImageName.parse("couchbase/server:7.6.2")) {
        override fun stop() {
          stopped = true
        }
      }
      val system = CouchbaseSystem(
        stove,
        CouchbaseContext(
          bucket = BucketDefinition("test"),
          runtime = container,
          options = CouchbaseSystemOptions(
            defaultBucket = "test",
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
