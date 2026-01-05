package com.stove.spring.standalone.example.e2e

import arrow.core.some
import com.trendyol.stove.testing.e2e.couchbase.couchbase
import com.trendyol.stove.testing.e2e.http.http
import com.trendyol.stove.testing.e2e.reporting.*
import com.trendyol.stove.testing.e2e.standalone.kafka.*
import com.trendyol.stove.testing.e2e.system.TestSystem
import com.trendyol.stove.testing.e2e.wiremock.wiremock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.*
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.string.shouldContain
import stove.spring.standalone.example.application.handlers.*
import stove.spring.standalone.example.application.services.SupplierPermission
import kotlin.time.Duration.Companion.seconds

class ReportingIntegrationTest :
  FunSpec({
    test("report should capture HTTP and Kafka operations") {
      TestSystem.validate {
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
      val report = TestSystem.reporter().currentTest()

      // Should have WireMock stub action
      report
        .entries()
        .filterIsInstance<ActionEntry>()
        .any { it.system == "WireMock" && it.action.contains("GET /suppliers") } shouldBe true

      // Should have HTTP action
      report
        .entries()
        .filterIsInstance<ActionEntry>()
        .any { it.system == "HTTP" && it.action.contains("POST /api/product") } shouldBe true

      // Should have Kafka assertion
      report
        .entries()
        .filterIsInstance<AssertionEntry>()
        .any { it.system == "Kafka" && it.description.contains("shouldBePublished") } shouldBe true
    }

    test("report should include Kafka MessageStore snapshot on failure") {
      try {
        TestSystem.validate {
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
      val snapshot = TestSystem.getSystem<KafkaSystem>(KafkaSystem::class).snapshot()
      snapshot shouldNotBe null
      snapshot.system shouldBe "Kafka"
      (snapshot.state["published"] as? List<*>)?.size?.shouldBeGreaterThan(0)
    }

    test("report should capture Couchbase operations") {
      TestSystem.validate {
        val request = ProductCreateRequest(200L, "couchbase test", 1L)
        val permission = SupplierPermission(request.supplierId, isAllowed = true)

        wiremock {
          mockGet("/suppliers/${permission.id}/allowed", 200, permission.some())
        }

        http {
          postAndExpectBodilessResponse("/api/product/create", request.some()) {
            it.status shouldBe 200
          }
        }

        couchbase {
          shouldGet<ProductCreateRequest>("product:${request.id}") {
            it.id shouldBe request.id
          }
        }
      }

      val report = TestSystem.reporter().currentTest()

      // Should have Couchbase combined action with result
      report
        .entries()
        .filterIsInstance<ActionEntry>()
        .any { it.system == "Couchbase" && it.action.contains("Get document") && it.result != null } shouldBe true
    }

    test("report should capture multiple system interactions") {
      TestSystem.validate {
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

        // Couchbase
        couchbase {
          shouldGet<ProductCreateRequest>("product:${request.id}") {
            it.id shouldBe request.id
          }
        }
      }

      val report = TestSystem.reporter().currentTest()
      // Use entriesForThisTest() to filter only entries for this specific test
      val entries = report.entriesForThisTest()

      // Should have entries from all systems
      // Note: Systems using recordActionWithResult create a single combined entry
      entries.filter { it.system == "WireMock" } shouldHaveSize 1
      entries.filter { it.system == "HTTP" } shouldHaveSize 1 // Combined action with result
      entries.filter { it.system == "Kafka" } shouldHaveSize 1
      entries.filter { it.system == "Couchbase" } shouldHaveSize 1 // Combined action with result
    }

    test("report should be renderable as JSON") {
      TestSystem.validate {
        http {
          get<String>("/api/index") {
            it shouldContain "Hi from Stove framework"
          }
        }
      }

      val report = TestSystem.reporter().currentTest()
      val json = JsonReportRenderer.render(report, emptyList())

      json shouldContain "testId"
      json shouldContain "testName"
      json shouldContain "entries"
      json shouldContain "summary"
      json shouldContain "HTTP"
    }

    test("report should be renderable as pretty console") {
      TestSystem.validate {
        http {
          get<String>("/api/index") {
            it shouldContain "Hi from Stove framework"
          }
        }
      }

      val report = TestSystem.reporter().currentTest()
      val pretty = PrettyConsoleRenderer.render(report, emptyList())

      pretty shouldContain "STOVE TEST EXECUTION REPORT"
      pretty shouldContain "[HTTP]"
      pretty shouldContain "╔"
      pretty shouldContain "╚"
    }
  })
