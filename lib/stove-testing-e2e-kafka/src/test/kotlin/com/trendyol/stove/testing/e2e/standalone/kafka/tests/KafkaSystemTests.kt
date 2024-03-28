package com.trendyol.stove.testing.e2e.standalone.kafka.tests

import com.trendyol.stove.testing.e2e.standalone.kafka.kafka
import com.trendyol.stove.testing.e2e.standalone.kafka.setup.DomainEvents.ProductCreated
import com.trendyol.stove.testing.e2e.standalone.kafka.setup.DomainEvents.ProductFailingCreated
import com.trendyol.stove.testing.e2e.system.TestSystem
import io.kotest.core.spec.style.FunSpec

class KafkaSystemTests : FunSpec({

    test("When publish then it should work") {
        TestSystem.validate {
            kafka {
                publish("product", ProductCreated("1"))
                publish("product", ProductCreated("2"))
                publish("product", ProductCreated("3"))
            }
        }

        // delay(5000)
    }

    test("When publish to a failing consumer should end-up throwing exception") {
        TestSystem.validate {
            kafka {
                publish("productFailing", ProductFailingCreated("1"))
                shouldBeConsumed(message = ProductFailingCreated("1"))
            }
        }

        // delay(5000)
    }
})
