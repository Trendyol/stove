package com.trendyol.stove.testing.e2e.standalone.kafka.tests

import arrow.core.some
import com.trendyol.stove.testing.e2e.standalone.kafka.kafka
import com.trendyol.stove.testing.e2e.standalone.kafka.setup.example.DomainEvents.ProductCreated
import com.trendyol.stove.testing.e2e.standalone.kafka.setup.example.DomainEvents.ProductFailingCreated
import com.trendyol.stove.testing.e2e.system.TestSystem.Companion.validate
import io.kotest.core.spec.style.FunSpec
import kotlinx.coroutines.async
import kotlin.random.Random
import kotlin.time.Duration.Companion.minutes

class KafkaSystemTests : FunSpec({
  val randomString = { Random.nextInt(0, Int.MAX_VALUE).toString() }

  test("When publish then it should work") {
    validate {
      kafka {
        val productId = randomString() + "[productCreated]"
        publish("product", message = ProductCreated(productId), key = randomString().some())
        shouldBePublished<ProductCreated> {
          actual.productId == productId
        }
        shouldBeConsumed<ProductCreated>(1.minutes) {
          actual.productId == productId
        }
      }
    }
  }
  test("lots of messages") {
    validate {
      kafka {
        val messages = ProductCreated.randoms(100)
        messages.map { async { publish("product", it, key = randomString().some()) } }
        messages.map { async { shouldBePublished<ProductCreated> { actual.productId == it.productId } } }
        messages.map { async { shouldBeConsumed<ProductCreated>(1.minutes) { actual.productId == it.productId } } }
      }
    }
  }

  test("When publish to a failing consumer should end-up throwing exception") {
    validate {
      kafka {
        val productId = randomString() + "[productFailingCreated]"
        publish("productFailing", ProductFailingCreated(productId), key = randomString().some())
        shouldBeRetried<ProductFailingCreated>(atLeastIn = 1.minutes, times = 3) {
          actual.productId == productId
        }

        shouldBePublished<ProductFailingCreated>(atLeastIn = 1.minutes) {
          this.metadata.topic == "productFailing.error"
        }
      }
    }
  }
})
