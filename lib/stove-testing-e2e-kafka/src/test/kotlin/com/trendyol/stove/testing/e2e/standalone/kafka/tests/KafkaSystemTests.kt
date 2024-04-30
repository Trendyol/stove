package com.trendyol.stove.testing.e2e.standalone.kafka.tests

import arrow.core.some
import com.trendyol.stove.testing.e2e.standalone.kafka.kafka
import com.trendyol.stove.testing.e2e.standalone.kafka.setup.DomainEvents.ProductCreated
import com.trendyol.stove.testing.e2e.standalone.kafka.setup.DomainEvents.ProductFailingCreated
import com.trendyol.stove.testing.e2e.system.TestSystem.Companion.validate
import io.kotest.core.spec.style.FunSpec
import kotlin.random.Random

class KafkaSystemTests : FunSpec({
  val randomString = { Random.nextInt(0, Int.MAX_VALUE).toString() }

  test("When publish then it should work") {
    validate {
      kafka {
        val productId = randomString() + "[productCreated]"
        publish("product", message = ProductCreated(productId), key = randomString().some())
        shouldBeConsumed<ProductCreated> {
          actual.productId == productId
        }
      }
    }
  }

  test("When publish to a failing consumer should end-up throwing exception") {
    validate {
      kafka {
        val productId = randomString() + "[productFailingCreated]"
        publish("productFailing", ProductFailingCreated(productId), key = randomString().some())
        shouldBeRetried<ProductFailingCreated>(times = 3) {
          actual.productId == productId
        }

        shouldBePublished<ProductFailingCreated> {
          this.metadata.topic == "productFailing.error"
        }
      }
    }
  }
})
