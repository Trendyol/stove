package com.stove.spring.standalone.example.e2e

import arrow.core.some
import com.trendyol.stove.http.http
import com.trendyol.stove.kafka.*
import com.trendyol.stove.postgres.postgresql
import com.trendyol.stove.reporting.*
import com.trendyol.stove.system.Stove
import com.trendyol.stove.system.stove
import com.trendyol.stove.wiremock.wiremock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.*
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.string.shouldContain
import stove.spring.standalone.example.application.handlers.*
import stove.spring.standalone.example.application.services.SupplierPermission
import java.util.*
import kotlin.time.Duration.Companion.seconds

class ReportingIntegrationTest :
  FunSpec({
    test("report should capture HTTP and Kafka operations") {
      stove {
        val request = ProductCreateRequest(100L, "test product", 1L)
        val permission = SupplierPermission(request.supplierId, isAllowed = true)

        wiremock {
          mockGet("/suppliers/${permission.id}/allowed", 200, permission.some())
        }

        http {
          postAndExpectBodilessResponse("/api/product/create", request.some()) {
            it.status shouldBe 200
          }
        }

        kafka {
          shouldBePublished<ProductCreatedEvent> {
            actual.id == request.id
          }
        }
      }

      // Validate the report contents
      val report = Stove.reporter().currentTest()

      // Should have WireMock stub action
      report
        .entries()
        .any { it.system == "WireMock" && it.action.contains("GET /suppliers") } shouldBe true

      // Should have HTTP action
      report
        .entries()
        .any { it.system == "HTTP" && it.action.contains("POST /api/product") } shouldBe true

      // Should have Kafka assertion
      report
        .entries()
        .any { it.system == "Kafka" && it.action.contains("shouldBePublished") } shouldBe true
    }

    test("report should include Kafka MessageStore snapshot on failure") {
      try {
        stove {
          kafka {
            publish("orders.test", mapOf("id" to 1))

            // This will fail - no consumer for this topic
            shouldBePublished<Map<String, Any>>(atLeastIn = 1.seconds) {
              actual["nonexistent"] == true
            }
          }
        }
      } catch (_: Throwable) {
        // Expected - can be TimeoutCancellationException, AssertionError, or wrapped exception
      }

      // Get the snapshot
      val snapshot = Stove.getSystem<KafkaSystem>(KafkaSystem::class).snapshot()
      snapshot shouldNotBe null
      snapshot.system shouldBe "Kafka"
      (snapshot.state["published"] as? List<*>)?.size?.shouldBeGreaterThan(0)
    }

    test("report should capture PostgreSQL operations") {
      stove {
        val request = ProductCreateRequest(200L, "postgres test", 1L)
        val permission = SupplierPermission(request.supplierId, isAllowed = true)

        wiremock {
          mockGet("/suppliers/${permission.id}/allowed", 200, permission.some())
        }

        http {
          postAndExpectBodilessResponse("/api/product/create", request.some()) {
            it.status shouldBe 200
          }
        }

        postgresql {
          shouldQuery<ProductCreateRequest>(
            "SELECT * FROM products WHERE id = ${request.id}",
            mapper = { row ->
              ProductCreateRequest(
                id = row.long("id"),
                name = row.string("name"),
                supplierId = row.long("supplier_id")
              )
            }
          ) { products ->
            products.size shouldBe 1
            products.first().id shouldBe request.id
          }
        }
      }

      val report = Stove.reporter().currentTest()

      // Should have PostgreSQL action with result
      report
        .entries()
        .any { it.system == "PostgreSQL" && it.action.contains("Query") } shouldBe true
    }

    test("report should capture multiple system interactions") {
      stove {
        val request = ProductCreateRequest(300L, "multi-system test", 1L)
        val permission = SupplierPermission(request.supplierId, isAllowed = true)

        // WireMock
        wiremock {
          mockGet("/suppliers/${permission.id}/allowed", 200, permission.some())
        }

        // HTTP
        http {
          postAndExpectBodilessResponse("/api/product/create", request.some()) {
            it.status shouldBe 200
          }
        }

        // Kafka
        kafka {
          shouldBePublished<ProductCreatedEvent> {
            actual.id == request.id
          }
        }

        // PostgreSQL
        postgresql {
          shouldQuery<ProductCreateRequest>(
            "SELECT * FROM products WHERE id = ${request.id}",
            mapper = { row ->
              ProductCreateRequest(
                id = row.long("id"),
                name = row.string("name"),
                supplierId = row.long("supplier_id")
              )
            }
          ) { products ->
            products.size shouldBe 1
            products.first().id shouldBe request.id
          }
        }
      }

      val report = Stove.reporter().currentTest()
      // Use entriesForThisTest() to filter only entries for this specific test
      val entries = report.entriesForThisTest()

      // Should have entries from all systems
      entries.filter { it.system == "WireMock" } shouldHaveSize 1
      entries.filter { it.system == "HTTP" } shouldHaveSize 1
      entries.filter { it.system == "Kafka" } shouldHaveSize 1
      entries.filter { it.system == "PostgreSQL" } shouldHaveSize 1
    }

    test("report should be renderable as JSON") {
      stove {
        http {
          get<String>("/api/index") {
            it shouldContain "Hi from Stove framework"
          }
        }
      }

      val report = Stove.reporter().currentTest()
      val json = JsonReportRenderer.render(report, emptyList())

      json shouldContain "testId"
      json shouldContain "testName"
      json shouldContain "entries"
      json shouldContain "summary"
      json shouldContain "HTTP"
    }

    test("report should be renderable as pretty console") {
      stove {
        http {
          get<String>("/api/index") {
            it shouldContain "Hi from Stove framework"
          }
        }
      }

      val report = Stove.reporter().currentTest()
      val pretty = PrettyConsoleRenderer.render(report, emptyList())

      pretty shouldContain "STOVE TEST EXECUTION REPORT"
      pretty shouldContain "[HTTP]"
      pretty shouldContain "╔"
      pretty shouldContain "╚"
    }
  })
