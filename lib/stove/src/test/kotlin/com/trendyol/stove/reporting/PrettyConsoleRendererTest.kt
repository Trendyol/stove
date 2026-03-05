package com.trendyol.stove.reporting

import arrow.core.Some
import com.trendyol.stove.tracing.TraceVisualization
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

class PrettyConsoleRendererTest :
  FunSpec({
    fun String.stripAnsi(): String = replace(Regex("\u001B\\[[0-9;]*m"), "")

    test("renders summary and timeline for successful entries") {
      val report = TestReport("test-1", "should process order")
      report.record(
        ReportEntry.success(
          system = "HTTP",
          testId = "test-1",
          action = "POST /api/orders",
          input = Some("{" + "\"id\":123}"),
          output = Some("201 Created")
        )
      )

      val rendered = PrettyConsoleRenderer.render(report, emptyList()).stripAnsi()

      rendered shouldContain "STOVE TEST EXECUTION REPORT"
      rendered shouldContain "should process order"
      rendered shouldContain "IN PROGRESS"
      rendered shouldContain "TIMELINE"
      rendered shouldContain "✓ PASSED"
      rendered shouldContain "POST /api/orders"
      rendered shouldContain "Input: {\"id\":123}"
      rendered shouldContain "Output: 201 Created"
    }

    test("renders failed assertions with expected actual and error details") {
      val report = TestReport("test-2", "should fail")
      report.record(
        ReportEntry.failure(
          system = "Kafka",
          testId = "test-2",
          action = "shouldBePublished<OrderEvent>",
          error = "expected:<2> but was:<1>",
          expected = Some(2),
          actual = Some(1)
        )
      )

      val rendered = PrettyConsoleRenderer.render(report, emptyList()).stripAnsi()

      rendered shouldContain "FAILED"
      rendered shouldContain "Expected: 2"
      rendered shouldContain "Actual: 1"
      rendered shouldContain "Error: expected:<2> but was:<1>"
    }

    test("groups sequential timeline entries by system") {
      val report = TestReport("test-2b", "grouped timeline")
      report.record(ReportEntry.success(system = "WireMock", testId = "test-2b", action = "Register stub A"))
      report.record(ReportEntry.success(system = "WireMock", testId = "test-2b", action = "Register stub B"))
      report.record(ReportEntry.success(system = "HTTP", testId = "test-2b", action = "GET /api/a"))
      report.record(ReportEntry.success(system = "HTTP", testId = "test-2b", action = "POST /api/b"))
      report.record(ReportEntry.success(system = "Kafka", testId = "test-2b", action = "Produce event"))

      val rendered = PrettyConsoleRenderer.render(report, emptyList()).stripAnsi()

      rendered shouldContain "WIREMOCK · 2 step(s)"
      rendered shouldContain "HTTP · 2 step(s)"
      rendered shouldContain "KAFKA · 1 step(s)"
      rendered shouldContain "#1 ✓ PASSED Register stub A"
      rendered shouldContain "#5 ✓ PASSED Produce event"
    }

    test("renders snapshots section with summary and state details") {
      val report = TestReport("test-3", "snapshot test")
      val snapshots = listOf(
        SystemSnapshot(
          system = "Kafka",
          summary = "Consumed: 1\nPublished: 0",
          state = mapOf(
            "consumed" to listOf(mapOf("topic" to "orders", "offset" to 42)),
            "failed" to emptyList<Any>()
          )
        )
      )

      val rendered = PrettyConsoleRenderer.render(report, snapshots).stripAnsi()

      rendered shouldContain "SYSTEM SNAPSHOTS"
      rendered shouldContain "KAFKA"
      rendered shouldContain "Summary"
      rendered shouldContain "Consumed: 1"
      rendered shouldContain "State"
      rendered shouldContain "consumed: 1 item(s)"
      rendered shouldContain "topic: orders"
      rendered shouldContain "offset: 42"
    }

    test("renders execution trace details when trace data exists") {
      val report = TestReport("test-4", "trace test")
      val trace = TraceVisualization(
        traceId = "trace-123",
        testId = "test-4",
        totalSpans = 2,
        failedSpans = 1,
        spans = emptyList(),
        tree = "root span\n└─ child span ✗",
        coloredTree = ""
      )

      report.record(
        ReportEntry.action(
          system = "HTTP",
          testId = "test-4",
          action = "POST /api/orders",
          passed = false,
          error = Some("500 Internal Server Error"),
          executionTrace = Some(trace)
        )
      )

      val rendered = PrettyConsoleRenderer.render(report, emptyList()).stripAnsi()

      rendered shouldContain "Execution Trace"
      rendered shouldContain "TraceId: trace-123"
      rendered shouldContain "Spans: 2 total / 1 failed"
      rendered shouldContain "root span"
      rendered shouldContain "child span"
    }

    test("renders empty timeline message for reports without entries") {
      val report = TestReport("test-5", "empty report")

      val rendered = PrettyConsoleRenderer.render(report, emptyList()).stripAnsi()

      rendered shouldContain "No actions recorded yet."
      rendered shouldNotContain "SYSTEM SNAPSHOTS"
    }

    test("does not truncate very long values") {
      val report = TestReport("test-6", "long value")
      val longWord = "x".repeat(220)

      report.record(
        ReportEntry.success(
          system = "HTTP",
          testId = "test-6",
          action = "POST /api/long",
          input = Some(longWord)
        )
      )

      val rendered = PrettyConsoleRenderer.render(report, emptyList()).stripAnsi()

      rendered shouldContain "Input:"
      rendered shouldContain longWord.take(40)
      rendered shouldContain longWord.takeLast(40)
      rendered shouldNotContain "..."
    }

    test("uses a compact width for small reports") {
      val report = TestReport("test-6b", "small report")
      report.record(ReportEntry.success(system = "HTTP", testId = "test-6b", action = "GET /health"))

      val rendered = PrettyConsoleRenderer.render(report, emptyList()).stripAnsi()
      val widths = rendered
        .lines()
        .filter { it.isNotBlank() }
        .map { it.length }
        .toSet()
      val width = widths.first()

      widths.size shouldBe 1
      (width < 120) shouldBe true
    }

    test("caps width for large reports") {
      val report = TestReport("test-6c", "large report")
      report.record(
        ReportEntry.failure(
          system = "HTTP",
          testId = "test-6c",
          action = "GET /very/long/endpoint/that/should/not/make/the/report/unreasonably/wide",
          error = "x".repeat(300)
        )
      )

      val rendered = PrettyConsoleRenderer.render(report, emptyList()).stripAnsi()
      val width = rendered.lines().first { it.isNotBlank() }.length

      (width <= 160) shouldBe true
    }

    test("renders real-world like report fixture for visual iteration") {
      val testId = "ExampleTest::should create new product when send product create request from api"
      val report = TestReport(testId, "should create new product when send product create request from api")

      report.record(
        ReportEntry.success(
          system = "WireMock",
          testId = testId,
          action = "Register stub: GET /api/suppliers/99",
          metadata = mapOf("priority" to 1, "responseStatus" to 200)
        )
      )

      report.record(
        ReportEntry.success(
          system = "WireMock",
          testId = testId,
          action = "Register stub: GET /inventory/products/1",
          metadata = mapOf("priority" to 1, "responseStatus" to 200)
        )
      )

      report.record(
        ReportEntry.success(
          system = "WireMock",
          testId = testId,
          action = "Register stub: POST /payments/charge",
          metadata = mapOf("priority" to 2, "responseStatus" to 200)
        )
      )

      report.record(
        ReportEntry.success(
          system = "WireMock",
          testId = testId,
          action = "Register stub: POST /inventory/sync",
          metadata = mapOf("priority" to 1, "responseStatus" to 200)
        )
      )

      report.record(
        ReportEntry.success(
          system = "HTTP",
          testId = testId,
          action = "GET /api/suppliers/99",
          output = Some("""{"status":200,"response":{"id":99,"name":"supplier name"}}""")
        )
      )

      report.record(
        ReportEntry.success(
          system = "Kafka",
          testId = testId,
          action = "KafkaProducer.send product command",
          output = Some("topic=trendyol.stove.service.productCommand.1 offset=41")
        )
      )

      report.record(
        ReportEntry.success(
          system = "PostgreSQL",
          testId = testId,
          action = "INSERT INTO outbox(product_id, status)",
          metadata = mapOf("rowsAffected" to 1, "table" to "outbox")
        )
      )

      report.record(
        ReportEntry.success(
          system = "Kafka",
          testId = testId,
          action = "KafkaConsumer.consume product command",
          output = Some("topic=trendyol.stove.service.productCommand.1 offset=41")
        )
      )

      report.record(
        ReportEntry.success(
          system = "HTTP",
          testId = testId,
          action = "POST /api/product/create",
          input = Some("ProductCreateRequest(id=1, name=product name, supplierId=99)"),
          output = Some("""{"status":201,"response":{"id":1,"status":"DRAFT"}}""")
        )
      )

      report.record(
        ReportEntry.success(
          system = "Kafka",
          testId = testId,
          action = "KafkaProducer.send product created event",
          output = Some("topic=trendyol.stove.service.productCreated.1 offset=0")
        )
      )

      report.record(
        ReportEntry.success(
          system = "PostgreSQL",
          testId = testId,
          action = "UPDATE outbox SET sent=true WHERE id=91",
          metadata = mapOf("rowsAffected" to 1, "table" to "outbox")
        )
      )

      report.record(
        ReportEntry.success(
          system = "WireMock",
          testId = testId,
          action = "Verify downstream call: POST /inventory/sync",
          metadata = mapOf("called" to true, "times" to 1)
        )
      )

      report.record(
        ReportEntry.success(
          system = "PostgreSQL",
          testId = testId,
          action = "SELECT status FROM products WHERE id=1",
          metadata = mapOf("rowsReturned" to 1, "table" to "products")
        )
      )

      val trace = TraceVisualization(
        traceId = "00-49242638d15b4e29ba49750d2089633f-87ab5cab1dd5b41d-01",
        testId = testId,
        totalSpans = 15,
        failedSpans = 1,
        spans = emptyList(),
        tree = """
        GET /api/products/1 [412ms] ✗
        | http.response.status_code: 200
        | http.route: /api/products/{id}
        | http.request.method: GET
        ProductQueryController.get [109ms] ✓
        ProductQueryService.findById [78ms] ✓
        PostgreSQL.queryProductById [44ms] ✓
        WireMock.inventory.getById [31ms] ✓
        KafkaProducer.send inventory-check [27ms] ✓
        HTTP.inventory.sync [29ms] ✓
        PostgreSQL.updateInventoryProjection [41ms] ✓
        InventorySyncHandler.handle [34ms] ✗
        | messaging.kafka.topic: trendyol.stove.service.inventorySync.1
        | error.type: INVENTORY_STATE_MISMATCH
        """.trimIndent(),
        coloredTree = ""
      )

      report.record(
        ReportEntry.action(
          system = "HTTP",
          testId = testId,
          action = "GET /api/products/1",
          passed = false,
          input = Some("ProductQueryRequest(id=1)"),
          output = Some("""{"status":200,"response":{"id":1,"status":"DRAFT"}}"""),
          metadata = mapOf("status" to 200, "headers" to emptyMap<String, String>()),
          expected = Some("Product status ACTIVE"),
          actual = Some("Product status DRAFT"),
          error = Some("expected:<ACTIVE> but was:<DRAFT>"),
          executionTrace = Some(trace)
        )
      )

      report.record(
        ReportEntry.success(
          system = "Kafka",
          testId = testId,
          action = "KafkaProducer.send compensation event",
          output = Some("topic=trendyol.stove.service.productCompensation.1 offset=2")
        )
      )

      report.record(
        ReportEntry.success(
          system = "HTTP",
          testId = testId,
          action = "POST /api/product/compensate",
          input = Some("""{"id":1,"reason":"STATUS_MISMATCH"}"""),
          output = Some("""{"status":202,"response":{"queued":true}}""")
        )
      )

      report.record(
        ReportEntry.success(
          system = "Kafka",
          testId = testId,
          action = "KafkaConsumer.consume compensation event",
          output = Some("topic=trendyol.stove.service.productCompensation.1 offset=2")
        )
      )

      report.record(
        ReportEntry.success(
          system = "WireMock",
          testId = testId,
          action = "Verify downstream call: GET /inventory/products/1",
          metadata = mapOf("called" to true, "times" to 2)
        )
      )

      val snapshots = listOf(
        SystemSnapshot(
          system = "HTTP",
          state = mapOf(
            "requests" to listOf(
              mapOf("method" to "GET", "path" to "/api/suppliers/99", "status" to 200),
              mapOf("method" to "POST", "path" to "/api/product/create", "status" to 201),
              mapOf("method" to "GET", "path" to "/api/products/1", "status" to 200),
              mapOf("method" to "POST", "path" to "/api/product/compensate", "status" to 202)
            ),
            "lastRequest" to mapOf("method" to "GET", "path" to "/api/products/1"),
            "lastResponse" to mapOf("status" to 200, "body" to mapOf("id" to 1, "status" to "DRAFT"))
          ),
          summary = "Requests (this test): 4\nLast response status: 200"
        ),
        SystemSnapshot(
          system = "Kafka",
          summary = """
            Consumed (this test): 3
            Produced (this test): 4
            Failed (this test): 1
          """.trimIndent(),
          state = mapOf(
            "consumed" to listOf(
              mapOf(
                "messageId" to "consumed-1",
                "topic" to "trendyol.stove.service.productCreated.1",
                "key" to 1,
                "offset" to 0,
                "headers" to mapOf(
                  "traceparent" to "00-49242638d15b4e29ba49750d2089633f-87ab5cab1dd5b41d-01",
                  "baggage" to "stove.test.id=$testId",
                  "__TypeId__" to "stove.spring.example4x.application.handlers.ProductCreatedEvent"
                ),
                "value" to mapOf("id" to 1, "name" to "product name", "status" to "DRAFT")
              ),
              mapOf(
                "messageId" to "consumed-2",
                "topic" to "trendyol.stove.service.productCommand.1",
                "key" to 1,
                "offset" to 41,
                "value" to mapOf("id" to 1, "command" to "CREATE", "tags" to listOf("new", "campaign"))
              ),
              mapOf(
                "messageId" to "consumed-3",
                "topic" to "trendyol.stove.service.productCompensation.1",
                "key" to 1,
                "offset" to 2,
                "value" to mapOf("id" to 1, "reason" to "STATUS_MISMATCH")
              )
            ),
            "produced" to listOf(
              mapOf(
                "topic" to "trendyol.stove.service.productCommand.1",
                "key" to 1,
                "value" to mapOf("id" to 1, "command" to "CREATE")
              ),
              mapOf(
                "topic" to "trendyol.stove.service.productCreated.1",
                "key" to 1,
                "value" to mapOf("id" to 1, "name" to "product name", "status" to "DRAFT")
              ),
              mapOf(
                "topic" to "trendyol.stove.service.productCompensation.1",
                "key" to 1,
                "value" to mapOf("id" to 1, "reason" to "STATUS_MISMATCH")
              ),
              mapOf(
                "topic" to "trendyol.stove.service.inventorySync.1",
                "key" to 1,
                "value" to mapOf("id" to 1, "expectedStatus" to "ACTIVE", "actualStatus" to "DRAFT")
              )
            ),
            "failed" to listOf(
              mapOf(
                "topic" to "trendyol.stove.service.inventorySync.1",
                "key" to 1,
                "reason" to "INVENTORY_STATE_MISMATCH",
                "payload" to mapOf("id" to 1, "expectedStatus" to "ACTIVE", "actualStatus" to "DRAFT")
              )
            )
          )
        ),
        SystemSnapshot(
          system = "PostgreSQL",
          summary = """
            Select queries: 4
            Insert queries: 2
            Update queries: 2
            Errors: 0
          """.trimIndent(),
          state = mapOf(
            "tables" to mapOf(
              "products" to listOf(
                mapOf("id" to 1, "name" to "product name", "status" to "DRAFT")
              ),
              "outbox" to listOf(
                mapOf("id" to 91, "type" to "ProductCreatedEvent", "sent" to true),
                mapOf("id" to 92, "type" to "ProductCompensationEvent", "sent" to false)
              )
            )
          )
        ),
        SystemSnapshot(
          system = "WireMock",
          summary = """
            Registered stubs (this test): 5
            Served requests (this test): 4 (matched: 4)
            Unmatched requests: 0
          """.trimIndent(),
          state = mapOf(
            "registeredStubs" to listOf(
              mapOf("method" to "GET", "url" to "/api/suppliers/99", "status" to 200),
              mapOf("method" to "POST", "url" to "/api/product/create", "status" to 201),
              mapOf("method" to "GET", "url" to "/inventory/products/1", "status" to 200),
              mapOf("method" to "POST", "url" to "/payments/charge", "status" to 200),
              mapOf("method" to "POST", "url" to "/inventory/sync", "status" to 200)
            ),
            "servedRequests" to listOf(
              mapOf("method" to "GET", "url" to "/api/suppliers/99", "matched" to true),
              mapOf("method" to "POST", "url" to "/api/product/create", "matched" to true),
              mapOf("method" to "GET", "url" to "/inventory/products/1", "matched" to true),
              mapOf("method" to "POST", "url" to "/inventory/sync", "matched" to true)
            ),
            "unmatchedRequests" to emptyList<Any>()
          )
        )
      )

      val rendered = PrettyConsoleRenderer.render(report, snapshots)
      val plainRendered = rendered.stripAnsi()

      println("\n" + "=".repeat(140))
      println("VISUAL ITERATION FIXTURE - REAL WORLD REPORT")
      println("=".repeat(140))
      println(rendered)
      println("=".repeat(140))

      plainRendered shouldContain "STOVE TEST EXECUTION REPORT"
      plainRendered shouldContain "TIMELINE"
      plainRendered shouldContain "SYSTEM SNAPSHOTS"
      plainRendered shouldContain "Execution Trace"
      plainRendered shouldContain "InventorySyncHandler.handle [34ms] ✗"
      plainRendered shouldContain "Failed (this test): 1"
      plainRendered shouldContain "expected:<ACTIVE> but was:<DRAFT>"
      plainRendered shouldContain "PostgreSQL"
      plainRendered shouldContain "KafkaProducer.send compensation event"
    }
  })
