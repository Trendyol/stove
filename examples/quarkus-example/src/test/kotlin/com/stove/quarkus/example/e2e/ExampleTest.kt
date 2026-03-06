package com.stove.quarkus.example.e2e

import arrow.core.some
import com.trendyol.stove.http.*
import com.trendyol.stove.kafka.kafka
import com.trendyol.stove.postgres.postgresql
import com.trendyol.stove.system.stove
import com.trendyol.stove.tracing.tracing
import com.trendyol.stove.wiremock.wiremock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.delay
import stove.quarkus.example.application.CreateProductCommand
import stove.quarkus.example.application.ProductCreateRequest
import stove.quarkus.example.application.ProductCreatedEvent
import stove.quarkus.example.application.SupplierPermission
import stove.quarkus.example.infrastructure.kafka.DEFAULT_USER_EMAIL_HEADER_VALUE
import stove.quarkus.example.infrastructure.kafka.USER_EMAIL_HEADER
import kotlin.time.Duration.Companion.seconds

class ExampleTest :
  FunSpec({
    val textPlainHeaders = mapOf("Accept" to "text/plain")

    data class PersistedProduct(
      val id: Long,
      val name: String,
      val supplierId: Long
    )

    test("index should be reachable") {
      stove {
        http {
          get<String>(
            "/api/index",
            queryParams = mapOf("keyword" to testCase.name.name),
            headers = textPlainHeaders
          ) { actual ->
            actual shouldContain "Hi from Stove Quarkus example with ${testCase.name.name}"
          }
        }
      }
    }

    test("should create new product when send product create request from api for the allowed supplier") {
      stove {
        val productCreateRequest = ProductCreateRequest(1L, name = "product name", supplierId = 99L)
        val supplierPermission = SupplierPermission(productCreateRequest.supplierId, isAllowed = true)

        wiremock {
          mockGet(
            "/suppliers/${productCreateRequest.supplierId}/allowed",
            statusCode = 200,
            responseBody = supplierPermission.some()
          )
        }

        http {
          postAndExpectBody<String>(
            "/api/product/create",
            body = productCreateRequest.some(),
            headers = textPlainHeaders
          ) { actual ->
            actual.status shouldBe 200
            actual.body() shouldBe "OK"
          }
        }

        kafka {
          shouldBePublished<ProductCreatedEvent>(5.seconds) {
            actual.id == productCreateRequest.id &&
              actual.name == productCreateRequest.name &&
              actual.supplierId == productCreateRequest.supplierId &&
              metadata.headers[USER_EMAIL_HEADER] == DEFAULT_USER_EMAIL_HEADER_VALUE
          }
        }

        postgresql {
          shouldQuery<PersistedProduct>(
            "SELECT * FROM products WHERE id = ${productCreateRequest.id}",
            mapper = { row ->
              PersistedProduct(
                row.long("id"),
                row.string("name"),
                row.long("supplier_id")
              )
            }
          ) { products ->
            products.size shouldBe 1
            products.first() shouldBe PersistedProduct(
              id = productCreateRequest.id,
              name = productCreateRequest.name,
              supplierId = productCreateRequest.supplierId
            )
          }
        }
      }
    }

    test("should return validation message when supplier is not allowed") {
      stove {
        val productCreateRequest = ProductCreateRequest(2L, name = "product name", supplierId = 98L)
        val supplierPermission = SupplierPermission(productCreateRequest.supplierId, isAllowed = false)

        wiremock {
          mockGet(
            "/suppliers/${productCreateRequest.supplierId}/allowed",
            statusCode = 200,
            responseBody = supplierPermission.some()
          )
        }

        http {
          postAndExpectBody<String>(
            "/api/product/create",
            body = productCreateRequest.some(),
            headers = textPlainHeaders
          ) { actual ->
            actual.status shouldBe 200
            actual.body() shouldBe
              "Supplier with the given id(${productCreateRequest.supplierId}) is not allowed for product creation"
          }
        }
      }
    }

    test("should create new product when send product create event for the allowed supplier") {
      stove {
        val createProductCommand = CreateProductCommand(4L, name = "product name", supplierId = 96L)
        val supplierPermission = SupplierPermission(createProductCommand.supplierId, isAllowed = true)

        wiremock {
          mockGet(
            "/suppliers/${createProductCommand.supplierId}/allowed",
            statusCode = 200,
            responseBody = supplierPermission.some()
          )
        }

        kafka {
          publish("trendyol.stove.service.product.create.0", createProductCommand)
          shouldBeConsumed<CreateProductCommand>(10.seconds) {
            actual.id == createProductCommand.id &&
              actual.name == createProductCommand.name &&
              actual.supplierId == createProductCommand.supplierId
          }
        }

        postgresql {
          shouldQuery<PersistedProduct>(
            "SELECT * FROM products WHERE id = ${createProductCommand.id}",
            mapper = { row ->
              PersistedProduct(
                row.long("id"),
                row.string("name"),
                row.long("supplier_id")
              )
            }
          ) { products ->
            products.size shouldBe 1
            products.first() shouldBe PersistedProduct(
              id = createProductCommand.id,
              name = createProductCommand.name,
              supplierId = createProductCommand.supplierId
            )
          }
        }

        kafka {
          shouldBePublished<ProductCreatedEvent>(10.seconds) {
            actual.id == createProductCommand.id &&
              actual.name == createProductCommand.name &&
              actual.supplierId == createProductCommand.supplierId
          }
        }
      }
    }

    test("tracing should capture quarkus request flow") {
      stove {
        val productCreateRequest = ProductCreateRequest(5L, name = "traced product", supplierId = 95L)
        val supplierPermission = SupplierPermission(productCreateRequest.supplierId, isAllowed = true)

        wiremock {
          mockGet(
            "/suppliers/${productCreateRequest.supplierId}/allowed",
            statusCode = 200,
            responseBody = supplierPermission.some()
          )
        }

        http {
          postAndExpectBody<String>(
            "/api/product/create",
            body = productCreateRequest.some(),
            headers = textPlainHeaders
          ) { actual ->
            actual.status shouldBe 200
            actual.body() shouldBe "OK"
          }
        }

        tracing {
          waitForExpectedSpans(
            expectedOperationNames = listOf(
              "ProductCreator.create",
              "SupplierHttpService.getSupplierPermission",
              "ProductEventPublisher.publish"
            ),
            timeoutMs = 15_000
          )
          shouldContainSpan("ProductCreator.create")
          shouldContainSpan("SupplierHttpService.getSupplierPermission")
          shouldContainSpan("ProductEventPublisher.publish")
          spanCountShouldBeAtLeast(4)
        }
      }
    }
  })

private suspend fun com.trendyol.stove.tracing.TracingValidationScope.waitForExpectedSpans(
  expectedOperationNames: List<String>,
  timeoutMs: Long
) {
  val deadline = System.currentTimeMillis() + timeoutMs

  while (System.currentTimeMillis() < deadline) {
    val spans = collector.getTrace(traceId)
    val operationNames = spans.map { it.operationName }
    val allExpectedSpansArePresent = expectedOperationNames.all { expectedOperationName ->
      operationNames.any { operationName -> operationName.contains(expectedOperationName) }
    }

    if (allExpectedSpansArePresent) {
      return
    }

    delay(250)
  }

  error(
    "Timeout waiting for spans: ${expectedOperationNames.joinToString()} in ${collector.getTrace(traceId).map { it.operationName }}"
  )
}
