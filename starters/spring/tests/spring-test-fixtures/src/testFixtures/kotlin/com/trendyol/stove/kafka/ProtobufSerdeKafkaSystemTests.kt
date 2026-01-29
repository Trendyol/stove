package com.trendyol.stove.kafka

import com.trendyol.stove.spring.testing.e2e.kafka.v1.*
import com.trendyol.stove.spring.testing.e2e.kafka.v1.Example.*
import com.trendyol.stove.system.stove
import io.kotest.core.spec.style.ShouldSpec
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds

/**
 * Shared Kafka protobuf serde tests that work across all Spring Boot versions.
 * Each version module should create their own test class that extends this
 * and provides the TestSystem setup.
 */
abstract class ProtobufSerdeKafkaSystemTests :
  ShouldSpec({
    should("publish and consume") {
      stove {
        kafka {
          val userId = Random.nextInt().toString()
          val productId = Random.nextInt().toString()
          val testProduct = product {
            id = productId
            name = "product-${Random.nextInt()}"
            price = Random.nextDouble()
            currency = "eur"
            description = "description-${Random.nextInt()}"
          }
          val headers = mapOf("x-user-id" to userId)
          publish("topic-protobuf", testProduct, headers = headers)
          shouldBePublished<Product>(20.seconds) {
            actual == testProduct && this.metadata.headers["x-user-id"] == userId && this.metadata.topic == "topic-protobuf"
          }
          shouldBeConsumed<Product>(20.seconds) {
            actual == testProduct && this.metadata.headers["x-user-id"] == userId && this.metadata.topic == "topic-protobuf"
          }

          val orderId = Random.nextInt().toString()
          val testOrder = order {
            id = orderId
            customerId = userId
            products += testProduct
          }
          publish("topic-protobuf", testOrder, headers = headers)
          shouldBePublished<Order>(20.seconds) {
            actual == testOrder && this.metadata.headers["x-user-id"] == userId && this.metadata.topic == "topic-protobuf"
          }

          shouldBeConsumed<Order>(20.seconds) {
            actual == testOrder && this.metadata.headers["x-user-id"] == userId && this.metadata.topic == "topic-protobuf"
          }
        }
      }
    }
  })
