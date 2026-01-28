package com.stove.spring.example.e2e

import arrow.core.some
import com.trendyol.stove.http.*
import com.trendyol.stove.kafka.kafka
import com.trendyol.stove.postgres.postgresql
import com.trendyol.stove.system.*
import com.trendyol.stove.wiremock.wiremock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.springframework.http.MediaType
import org.springframework.r2dbc.core.DatabaseClient
import stove.spring.example.application.handlers.*
import stove.spring.example.application.services.SupplierPermission
import stove.spring.example.infrastructure.messaging.kafka.consumers.*
import kotlin.time.Duration.Companion.seconds

class ExampleTest :
  FunSpec({
    test("bridge should work") {
      stove {
        using<DatabaseClient> {
          this shouldNotBe null
        }
      }
    }

    test("index should be reachable") {
      stove {
        http {
          get<String>("/api/index", queryParams = mapOf("keyword" to testCase.name.name)) { actual ->
            actual shouldContain "Hi from Stove framework with ${testCase.name.name}"
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
      stove {
        val productCreateRequest = ProductCreateRequest(1L, name = "product name", 99L)
        val supplierPermission = SupplierPermission(productCreateRequest.supplierId, isAllowed = true)

        wiremock {
          mockGet(
            "/suppliers/${productCreateRequest.id}/allowed",
            statusCode = 200,
            responseBody = supplierPermission.some()
          )
        }

        http {
          postAndExpectBodilessResponse(uri = "/api/product/create", body = productCreateRequest.some()) { actual ->
            actual.status shouldBe 200
          }
        }

        kafka {
          shouldBePublished<ProductCreatedEvent> {
            actual.id == productCreateRequest.id &&
              actual.name == productCreateRequest.name &&
              actual.supplierId == productCreateRequest.supplierId
          }
        }

        postgresql {
          shouldQuery<ProductCreateRequest>(
            "SELECT * FROM products WHERE id = ${productCreateRequest.id}",
            mapper = { row ->
              ProductCreateRequest(
                row.long("id"),
                row.string("name"),
                row.long("supplier_id")
              )
            }
          ) { products ->
            products.size shouldBe 1
            products.first().id shouldBe productCreateRequest.id
            products.first().name shouldBe productCreateRequest.name
            products.first().supplierId shouldBe productCreateRequest.supplierId
          }
        }
      }
    }

    test("should throw error when send product create request from api for for the not allowed supplier") {
      stove {
        val productCreateRequest = ProductCreateRequest(2L, name = "product name", 98L)
        val supplierPermission = SupplierPermission(productCreateRequest.supplierId, isAllowed = false)
        wiremock {
          mockGet(
            "/suppliers/${productCreateRequest.id}/allowed",
            statusCode = 200,
            responseBody = supplierPermission.some()
          )
        }
        http {
          postAndExpectJson<String>(uri = "/api/product/create", body = productCreateRequest.some()) { actual ->
            actual shouldBe "Supplier with the given id(${productCreateRequest.supplierId}) is not allowed for product creation"
          }
        }
      }
    }

    test("should throw error when send product create event for the not allowed supplier") {
      stove {
        val command = CreateProductCommand(3L, name = "product name", 97L)
        val supplierPermission = SupplierPermission(command.supplierId, isAllowed = false)

        wiremock {
          mockGet(
            "/suppliers/${command.id}/allowed",
            statusCode = 200,
            responseBody = supplierPermission.some()
          )
        }

        kafka {
          publish("trendyol.stove.service.product.create.0", command)
          shouldBeConsumed<CreateProductCommand>(10.seconds) {
            actual.id == command.id
          }
        }
      }
    }

    test("should create new product when send product create event for the allowed supplier") {
      stove {
        val createProductCommand = CreateProductCommand(4L, name = "product name", 96L)
        val supplierPermission = SupplierPermission(createProductCommand.supplierId, isAllowed = true)

        wiremock {
          mockGet(
            "/suppliers/${createProductCommand.id}/allowed",
            statusCode = 200,
            responseBody = supplierPermission.some()
          )
        }

        kafka {
          publish("trendyol.stove.service.product.create.0", createProductCommand)
          shouldBeConsumed<CreateProductCommand> {
            actual.id == createProductCommand.id &&
              actual.name == createProductCommand.name &&
              actual.supplierId == createProductCommand.supplierId &&
              metadata.headers["X-UserEmail"] == "stove@trendyol.com"
          }
        }

        postgresql {
          shouldQuery<ProductCreateRequest>(
            "SELECT * FROM products WHERE id = ${createProductCommand.id}",
            mapper = { row ->
              ProductCreateRequest(
                row.long("id"),
                row.string("name"),
                row.long("supplier_id")
              )
            }
          ) { products ->
            products.size shouldBe 1
            products.first().id shouldBe createProductCommand.id
            products.first().name shouldBe createProductCommand.name
            products.first().supplierId shouldBe createProductCommand.supplierId
          }
        }

        kafka {
          shouldBePublished<ProductCreatedEvent> {
            actual.id == createProductCommand.id &&
              actual.name == createProductCommand.name &&
              actual.supplierId == createProductCommand.supplierId
          }
        }
      }
    }

    test("when failing event is published then it should be validated") {
      stove {
        kafka {
          publish("trendyol.stove.service.product.failing.0", FailingEvent(5L))
          shouldBeFailed<FailingEvent> {
            actual.id == 5L && reason is BusinessException
          }

          shouldBeFailed<FailingEvent> {
            actual == FailingEvent(5L) && reason is BusinessException
          }
        }
      }
    }

    test("file import should work") {
      stove {
        http {
          postMultipartAndExpectResponse<String>(
            "/api/product/import",
            body = listOf(
              StoveMultiPartContent.Text("name", "product name"),
              StoveMultiPartContent.File(
                "file",
                "file.txt",
                "file".toByteArray(),
                contentType = MediaType.APPLICATION_OCTET_STREAM_VALUE
              )
            )
          ) { actual ->
            actual.body() shouldBe "File file.txt is imported with product name and content: file"
          }
        }
      }
    }
  })
