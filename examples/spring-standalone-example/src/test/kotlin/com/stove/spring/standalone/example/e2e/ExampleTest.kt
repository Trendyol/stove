package com.stove.spring.standalone.example.e2e

import arrow.core.some
import com.trendyol.stove.testing.e2e.couchbase.couchbase
import com.trendyol.stove.testing.e2e.http.*
import com.trendyol.stove.testing.e2e.standalone.kafka.kafka
import com.trendyol.stove.testing.e2e.system.*
import com.trendyol.stove.testing.e2e.wiremock.wiremock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.springframework.http.MediaType
import stove.spring.standalone.example.application.handlers.*
import stove.spring.standalone.example.application.services.SupplierPermission
import stove.spring.standalone.example.infrastructure.couchbase.CouchbaseProperties
import stove.spring.standalone.example.infrastructure.messaging.kafka.consumers.CreateProductCommand
import kotlin.time.Duration.Companion.seconds

class ExampleTest : FunSpec({
  test("bridge should work") {
    TestSystem.validate {
      using<CouchbaseProperties> {
        this.bucketName shouldBe "Stove"
      }
    }
  }

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
        mockGet(
          "/suppliers/${supplierPermission.id}/allowed",
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
        mockGet(
          "/suppliers/${supplierPermission.id}/allowed",
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
    TestSystem.validate {
      val command = CreateProductCommand(3L, name = "product name", 97L)
      val supplierPermission = SupplierPermission(command.supplierId, isAllowed = false)

      wiremock {
        mockGet(
          "/suppliers/${supplierPermission.id}/allowed",
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
    TestSystem.validate {
      val createProductCommand = CreateProductCommand(4L, name = "product name", 96L)
      val supplierPermission = SupplierPermission(createProductCommand.supplierId, isAllowed = true)

      wiremock {
        mockGet(
          "/suppliers/${supplierPermission.id}/allowed",
          statusCode = 200,
          responseBody = supplierPermission.some()
        )
      }

      kafka {
        publish("trendyol.stove.service.product.create.0", createProductCommand)
        shouldBeConsumed<CreateProductCommand> {
          actual.id == createProductCommand.id &&
            actual.name == createProductCommand.name &&
            actual.supplierId == createProductCommand.supplierId
        }

        shouldBePublished<ProductCreatedEvent> {
          actual.id == createProductCommand.id &&
            actual.name == createProductCommand.name &&
            actual.supplierId == createProductCommand.supplierId &&
            metadata.headers["X-UserEmail"] == "stove@trendyol.com"
        }
      }

      couchbase {
        shouldGet<ProductCreateRequest>("product:${createProductCommand.id}") { actual ->
          actual.id shouldBe createProductCommand.id
          actual.name shouldBe createProductCommand.name
          actual.supplierId shouldBe createProductCommand.supplierId
        }
      }
    }
  }

  test("when failing event is published then it should be validated") {
    data class FailingEvent(val id: Long)
    TestSystem.validate {
      kafka {
        publish("trendyol.stove.service.product.failing.0", FailingEvent(5L))
        shouldBeFailed<FailingEvent> {
          actual.id == 5L
        }

        shouldBeFailed<FailingEvent> {
          actual == FailingEvent(5L)
        }
      }
    }
  }

  test("file import should work") {
    TestSystem.validate {
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
