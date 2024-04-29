package com.trendyol.stove.testing.e2e.standalone.kafka.tests

import com.trendyol.stove.testing.e2e.standalone.kafka.kafka
import com.trendyol.stove.testing.e2e.standalone.kafka.setup.DomainEvents.ProductCreated
import com.trendyol.stove.testing.e2e.standalone.kafka.setup.DomainEvents.ProductFailingCreated
import com.trendyol.stove.testing.e2e.system.TestSystem.Companion.validate
import io.kotest.core.spec.style.FunSpec

class KafkaSystemTests : FunSpec({

  test("When publish then it should work") {
    validate {
      kafka {
        publish("product", ProductCreated("1"))
        shouldBeConsumed<ProductCreated> {
          actual == ProductCreated("1")
        }
      }
    }
  }

  test("When publish to a failing consumer should end-up throwing exception") {
    validate {
      kafka {
        publish("productFailing", ProductFailingCreated("1"))
        shouldBeConsumed<ProductFailingCreated> {
          actual == ProductFailingCreated("1")
        }
      }
    }
  }
})
