package com.stove.spring.example.e2e

import arrow.core.some
import com.trendyol.stove.testing.e2e.couchbase.couchbase
import com.trendyol.stove.testing.e2e.database.DocumentDatabaseSystem.Companion.shouldGet
import com.trendyol.stove.testing.e2e.http.HttpSystem.Companion.get
import com.trendyol.stove.testing.e2e.http.HttpSystem.Companion.postAndExpectJson
import com.trendyol.stove.testing.e2e.http.http
import com.trendyol.stove.testing.e2e.kafka.kafka
import com.trendyol.stove.testing.e2e.messaging.AssertsConsuming.Companion.shouldBeConsumedOnCondition
import com.trendyol.stove.testing.e2e.messaging.AssertsPublishing.Companion.shouldBePublishedOnCondition
import com.trendyol.stove.testing.e2e.system.TestSystem
import com.trendyol.stove.testing.e2e.wiremock.wiremock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import stove.spring.example.application.handlers.ProductCreateRequest
import stove.spring.example.application.handlers.ProductCreatedEvent
import stove.spring.example.application.services.SupplierPermission
import stove.spring.example.infrastructure.messaging.kafka.consumers.ProductCreateEvent

class ExampleTest : FunSpec({
    test("index should be reachable") {
        TestSystem.validate {
            http {
                get<String>("/api/index", queryParams = mapOf("keyword" to testCase.name.testName)) { actual ->
                    actual shouldContain "Hi from Stove framework with ${testCase.name.testName}"
                    println(actual)
                }
                get<String>("/api/index") { actual ->
                    actual shouldContain "Hi from Stove framework with"
                    println(actual)
                }
            }
        }
    }

    test("should create new product when send product create request from api for the allowed supplier") {
        TestSystem.validate {
            val productCreateRequest = ProductCreateRequest(1L, name = "product name", 99L)
            val supplierPermission = SupplierPermission(productCreateRequest.supplierId, isAllowed = true)

            wiremock {
                mockGet("/suppliers/${productCreateRequest.id}/allowed", responseBody = supplierPermission.some(), statusCode = 200)
            }

            http {
                postAndExpectJson<String>(uri = "/api/product/create", body = productCreateRequest.some()) { actual ->
                    actual shouldBe "OK"
                }
            }

            kafka {
                shouldBePublishedOnCondition<ProductCreatedEvent> { actual ->
                    actual.id == productCreateRequest.id &&
                        actual.name == productCreateRequest.name &&
                        actual.supplierId == productCreateRequest.supplierId
                }
            }

            couchbase {
                shouldGet<ProductCreateRequest>("product:${productCreateRequest.id}") { actual ->
                    actual.id shouldBe productCreateRequest.id
                    actual.name shouldBe productCreateRequest.name
                    actual.supplierId shouldBe productCreateRequest.supplierId
                }
            }
        }
    }

    test("should throw error when send product create request from api for for the not allowed supplier") {
        TestSystem.validate {
            val productCreateRequest = ProductCreateRequest(2L, name = "product name", 98L)
            val supplierPermission = SupplierPermission(productCreateRequest.supplierId, isAllowed = false)
            wiremock {
                mockGet("/suppliers/${productCreateRequest.id}/allowed", responseBody = supplierPermission.some(), statusCode = 200)
                http {
                    postAndExpectJson<String>(uri = "/api/product/create", body = productCreateRequest.some()) { actual ->
                        actual shouldBe "Supplier with the given id(${productCreateRequest.supplierId}) is not allowed for product creation"
                    }
                }
            }
        }
    }

    test("should throw error when send product create event for the not allowed supplier") {
        TestSystem.validate {
            val productCreateEvent = ProductCreateEvent(3L, name = "product name", 97L)
            val supplierPermission = SupplierPermission(productCreateEvent.supplierId, isAllowed = false)

            wiremock {
                mockGet("/suppliers/${productCreateEvent.id}/allowed", responseBody = supplierPermission.some(), statusCode = 200)
            }

            kafka {
                publish("trendyol.stove.service.product.create.0", productCreateEvent)
                shouldBeConsumedOnCondition<ProductCreateEvent> { actual ->
                    actual.id == productCreateEvent.id
                }
            }
        }
    }

    test("should create new product when send product create event for the allowed supplier") {
        TestSystem.validate {
            val productCreateEvent = ProductCreateEvent(4L, name = "product name", 96L)
            val supplierPermission = SupplierPermission(productCreateEvent.supplierId, isAllowed = true)

            wiremock {
                mockGet("/suppliers/${productCreateEvent.id}/allowed", responseBody = supplierPermission.some(), statusCode = 200)
            }

            kafka {
                publish("trendyol.stove.service.product.create.0", productCreateEvent)
                shouldBeConsumedOnCondition<ProductCreateEvent> { actual ->
                    actual.id == productCreateEvent.id
                }
                shouldBePublishedOnCondition<ProductCreatedEvent> { actual ->
                    actual.id == productCreateEvent.id &&
                        actual.name == productCreateEvent.name &&
                        actual.supplierId == productCreateEvent.supplierId
                }
            }

            couchbase {
                shouldGet<ProductCreateRequest>("product:${productCreateEvent.id}") { actual ->
                    actual.id shouldBe productCreateEvent.id
                    actual.name shouldBe productCreateEvent.name
                    actual.supplierId shouldBe productCreateEvent.supplierId
                }
            }
        }
    }
})
