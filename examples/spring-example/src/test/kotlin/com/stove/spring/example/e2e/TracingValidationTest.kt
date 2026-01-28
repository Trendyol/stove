package com.stove.spring.example.e2e

import arrow.core.some
import com.trendyol.stove.http.*
import com.trendyol.stove.system.*
import com.trendyol.stove.tracing.*
import com.trendyol.stove.wiremock.wiremock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import stove.spring.example.application.handlers.ProductCreateRequest
import stove.spring.example.application.services.SupplierPermission

class TracingValidationTest :
  FunSpec({

    test("tracing should capture HTTP request context implicitly") {
      stove {
        // Trace is auto-started, just make HTTP call
        http {
          get<String>("/api/index", queryParams = mapOf("keyword" to "tracing-test")) { actual ->
            actual shouldContain "Hi from Stove framework"
          }
        }

        // Access trace context for validation - all props accessible directly
        tracing {
          traceId.length shouldBe 32
          rootSpanId.length shouldBe 16

          val traceparent = toTraceparent()
          traceparent shouldContain traceId

          println("✓ Trace context created implicitly:")
          println("  - traceId: $traceId")
          println("  - testId: $testId")
          println("  - traceparent: $traceparent")
        }
      }
    }

    test("tracing should work with full request flow") {
      stove {
        val productCreateRequest = ProductCreateRequest(100L, name = "traced product", 999L)
        val supplierPermission = SupplierPermission(productCreateRequest.supplierId, isAllowed = true)

        wiremock {
          mockGet(
            "/suppliers/${productCreateRequest.supplierId}/allowed",
            statusCode = 200,
            responseBody = supplierPermission.some()
          )
        }

        http {
          postAndExpectBodilessResponse(uri = "/api/product/create", body = productCreateRequest.some()) { actual ->
            actual.status shouldBe 200
          }
        }

        // Validate trace was captured
        tracing {
          println("✓ Full request flow traced:")
          println("  - traceId: $traceId")
          println("  - testId: $testId")
        }
      }
    }

    test("trace collector should record spans") {
      stove {
        tracing {
          // Record test spans
          collector.record(
            SpanInfo(
              traceId = traceId,
              spanId = TraceContext.generateSpanId(),
              parentSpanId = rootSpanId,
              operationName = "TestController.handleRequest",
              serviceName = "collector-test",
              startTimeNanos = System.nanoTime(),
              endTimeNanos = System.nanoTime() + 1_000_000,
              status = SpanStatus.OK
            )
          )

          collector.record(
            SpanInfo(
              traceId = traceId,
              spanId = TraceContext.generateSpanId(),
              parentSpanId = rootSpanId,
              operationName = "TestService.processData",
              serviceName = "collector-test",
              startTimeNanos = System.nanoTime(),
              endTimeNanos = System.nanoTime() + 2_000_000,
              status = SpanStatus.OK
            )
          )

          // Validate spans - methods available directly
          shouldContainSpan("TestController.handleRequest")
          shouldContainSpan("TestService.processData")
          shouldNotHaveFailedSpans()
          spanCountShouldBeAtLeast(2)

          println("✓ Trace collector recorded spans:")
          println(renderTree())
        }
      }
    }
  })
