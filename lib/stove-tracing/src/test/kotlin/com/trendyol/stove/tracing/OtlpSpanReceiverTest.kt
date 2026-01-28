package com.trendyol.stove.tracing

import arrow.core.getOrElse
import io.grpc.ManagedChannelBuilder
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest
import io.opentelemetry.proto.collector.trace.v1.TraceServiceGrpc
import io.opentelemetry.proto.common.v1.AnyValue
import io.opentelemetry.proto.common.v1.KeyValue
import io.opentelemetry.proto.resource.v1.Resource
import io.opentelemetry.proto.trace.v1.ResourceSpans
import io.opentelemetry.proto.trace.v1.ScopeSpans
import io.opentelemetry.proto.trace.v1.Span
import io.opentelemetry.proto.trace.v1.Status
import java.util.concurrent.TimeUnit

private const val TEST_STACKTRACE = """java.lang.RuntimeException: Something went wrong
	at com.example.Service.process(Service.kt:42)
	at com.example.Controller.handle(Controller.kt:15)
	at sun.reflect.NativeMethodAccessorImpl.invoke0(Native Method)"""

class OtlpSpanReceiverTest :
  FunSpec({
    val testPort = 14317 // Use a non-standard port to avoid conflicts

    test("start should succeed on available port") {
      val collector = StoveTraceCollector()
      val receiver = OTLPSpanReceiver(collector, testPort)

      try {
        val result = receiver.start()
        result.isRight() shouldBe true
        receiver.endpoint shouldBe "http://localhost:$testPort"
      } finally {
        receiver.stop()
      }
    }

    test("start should be idempotent") {
      val collector = StoveTraceCollector()
      val receiver = OTLPSpanReceiver(collector, testPort)

      try {
        receiver.start()
        val result = receiver.start() // Second start should also succeed
        result.isRight() shouldBe true
      } finally {
        receiver.stop()
      }
    }

    test("stop should be safe to call multiple times") {
      val collector = StoveTraceCollector()
      val receiver = OTLPSpanReceiver(collector, testPort)

      receiver.start()
      receiver.stop()
      receiver.stop() // Should not throw
    }

    test("export should record spans to collector") {
      val collector = StoveTraceCollector()
      val receiver = OTLPSpanReceiver(collector, testPort)

      try {
        receiver.start()

        // Create a gRPC client
        val channel = ManagedChannelBuilder
          .forAddress("localhost", testPort)
          .usePlaintext()
          .build()

        try {
          val stub = TraceServiceGrpc.newBlockingStub(channel)

          // Create a test span
          val request = createExportRequest(
            serviceName = "test-service",
            traceId = "0123456789abcdef0123456789abcdef",
            spanId = "0123456789abcdef",
            spanName = "test-operation"
          )

          stub.export(request)

          // Give some time for the span to be recorded
          Thread.sleep(100)

          val trace = collector.getTrace("0123456789abcdef0123456789abcdef")
          trace shouldHaveSize 1
          trace[0].operationName shouldBe "test-operation"
          trace[0].serviceName shouldBe "test-service"
        } finally {
          channel.shutdown()
          channel.awaitTermination(5, TimeUnit.SECONDS)
        }
      } finally {
        receiver.stop()
      }
    }

    test("export should filter out internal gRPC spans") {
      val collector = StoveTraceCollector()
      val receiver = OTLPSpanReceiver(collector, testPort)

      try {
        receiver.start()

        val channel = ManagedChannelBuilder
          .forAddress("localhost", testPort)
          .usePlaintext()
          .build()

        try {
          val stub = TraceServiceGrpc.newBlockingStub(channel)

          // Create spans including internal gRPC span that should be filtered
          val request = createExportRequest(
            serviceName = "test-service",
            traceId = "abcdef0123456789abcdef0123456789",
            spanId = "fedcba9876543210",
            spanName = "TraceService/Export" // This should be filtered out
          )

          stub.export(request)

          Thread.sleep(100)

          // The internal span should be filtered out
          val trace = collector.getTrace("abcdef0123456789abcdef0123456789")
          trace shouldHaveSize 0
        } finally {
          channel.shutdown()
          channel.awaitTermination(5, TimeUnit.SECONDS)
        }
      } finally {
        receiver.stop()
      }
    }

    test("export should extract service name from resource attributes") {
      val collector = StoveTraceCollector()
      val receiver = OTLPSpanReceiver(collector, testPort)

      try {
        receiver.start()

        val channel = ManagedChannelBuilder
          .forAddress("localhost", testPort)
          .usePlaintext()
          .build()

        try {
          val stub = TraceServiceGrpc.newBlockingStub(channel)

          val request = createExportRequest(
            serviceName = "my-custom-service",
            traceId = "11111111111111111111111111111111",
            spanId = "2222222222222222",
            spanName = "custom-operation"
          )

          stub.export(request)

          Thread.sleep(100)

          val trace = collector.getTrace("11111111111111111111111111111111")
          trace shouldHaveSize 1
          trace[0].serviceName shouldBe "my-custom-service"
        } finally {
          channel.shutdown()
          channel.awaitTermination(5, TimeUnit.SECONDS)
        }
      } finally {
        receiver.stop()
      }
    }

    test("export should handle multiple spans in single request") {
      val collector = StoveTraceCollector()
      val receiver = OTLPSpanReceiver(collector, testPort)

      try {
        receiver.start()

        val channel = ManagedChannelBuilder
          .forAddress("localhost", testPort)
          .usePlaintext()
          .build()

        try {
          val stub = TraceServiceGrpc.newBlockingStub(channel)

          val traceId = "33333333333333333333333333333333"
          val request = createExportRequestWithMultipleSpans(
            serviceName = "test-service",
            traceId = traceId,
            spanNames = listOf("span1", "span2", "span3")
          )

          stub.export(request)

          Thread.sleep(100)

          val trace = collector.getTrace(traceId)
          trace shouldHaveSize 3
          trace.map { it.operationName } shouldBe listOf("span1", "span2", "span3")
        } finally {
          channel.shutdown()
          channel.awaitTermination(5, TimeUnit.SECONDS)
        }
      } finally {
        receiver.stop()
      }
    }

    test("export should handle span with error status") {
      val collector = StoveTraceCollector()
      val receiver = OTLPSpanReceiver(collector, testPort)

      try {
        receiver.start()

        val channel = ManagedChannelBuilder
          .forAddress("localhost", testPort)
          .usePlaintext()
          .build()

        try {
          val stub = TraceServiceGrpc.newBlockingStub(channel)

          val traceId = "44444444444444444444444444444444"
          val request = createExportRequestWithErrorSpan(
            serviceName = "test-service",
            traceId = traceId,
            spanId = "5555555555555555",
            spanName = "failed-operation",
            errorMessage = "Something went wrong"
          )

          stub.export(request)

          Thread.sleep(100)

          val trace = collector.getTrace(traceId)
          trace shouldHaveSize 1
          trace[0].status shouldBe SpanStatus.ERROR
          trace[0].exception?.message shouldBe "Something went wrong"
        } finally {
          channel.shutdown()
          channel.awaitTermination(5, TimeUnit.SECONDS)
        }
      } finally {
        receiver.stop()
      }
    }

    test("export should extract exception from span events") {
      val collector = StoveTraceCollector()
      val receiver = OTLPSpanReceiver(collector, testPort)

      try {
        receiver.start()

        val channel = ManagedChannelBuilder
          .forAddress("localhost", testPort)
          .usePlaintext()
          .build()

        try {
          val stub = TraceServiceGrpc.newBlockingStub(channel)

          val traceId = "88888888888888888888888888888888"
          val request = createExportRequestWithExceptionEvent(
            serviceName = "test-service",
            traceId = traceId,
            spanId = "9999999999999999",
            spanName = "failed-operation",
            exceptionType = "java.lang.RuntimeException",
            exceptionMessage = "Something went wrong",
            exceptionStacktrace = TEST_STACKTRACE
          )

          stub.export(request)

          Thread.sleep(100)

          val trace = collector.getTrace(traceId)
          trace shouldHaveSize 1
          trace[0].status shouldBe SpanStatus.ERROR
          trace[0].exception?.type shouldBe "java.lang.RuntimeException"
          trace[0].exception?.message shouldBe "Something went wrong"
          trace[0].exception?.stackTrace?.size shouldBe 4
          trace[0].exception?.stackTrace?.get(1) shouldBe "at com.example.Service.process(Service.kt:42)"
        } finally {
          channel.shutdown()
          channel.awaitTermination(5, TimeUnit.SECONDS)
        }
      } finally {
        receiver.stop()
      }
    }

    test("export should extract span attributes") {
      val collector = StoveTraceCollector()
      val receiver = OTLPSpanReceiver(collector, testPort)

      try {
        receiver.start()

        val channel = ManagedChannelBuilder
          .forAddress("localhost", testPort)
          .usePlaintext()
          .build()

        try {
          val stub = TraceServiceGrpc.newBlockingStub(channel)

          val traceId = "66666666666666666666666666666666"
          val request = createExportRequestWithAttributes(
            serviceName = "test-service",
            traceId = traceId,
            spanId = "7777777777777777",
            spanName = "db-operation",
            attributes = mapOf(
              "db.system" to "postgresql",
              "db.name" to "test_db"
            )
          )

          stub.export(request)

          Thread.sleep(100)

          val trace = collector.getTrace(traceId)
          trace shouldHaveSize 1
          trace[0].attributes["db.system"] shouldBe "postgresql"
          trace[0].attributes["db.name"] shouldBe "test_db"
        } finally {
          channel.shutdown()
          channel.awaitTermination(5, TimeUnit.SECONDS)
        }
      } finally {
        receiver.stop()
      }
    }

    test("start should fail on port already in use") {
      val collector = StoveTraceCollector()
      val receiver1 = OTLPSpanReceiver(collector, testPort)
      val receiver2 = OTLPSpanReceiver(collector, testPort)

      try {
        receiver1.start()
        val result = receiver2.start()

        result.isLeft() shouldBe true
        result.getOrElse { it }.shouldBeInstanceOf<OTLPReceiverError.StartupFailed>()
      } finally {
        receiver1.stop()
        receiver2.stop()
      }
    }
  })

private fun hexStringToByteString(hex: String): com.google.protobuf.ByteString {
  val bytes = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
  return com.google.protobuf.ByteString
    .copyFrom(bytes)
}

private fun createExportRequest(
  serviceName: String,
  traceId: String,
  spanId: String,
  spanName: String
): ExportTraceServiceRequest {
  val resource = Resource
    .newBuilder()
    .addAttributes(
      KeyValue
        .newBuilder()
        .setKey("service.name")
        .setValue(AnyValue.newBuilder().setStringValue(serviceName))
    ).build()

  val span = Span
    .newBuilder()
    .setTraceId(hexStringToByteString(traceId))
    .setSpanId(hexStringToByteString(spanId))
    .setName(spanName)
    .setStartTimeUnixNano(System.nanoTime())
    .setEndTimeUnixNano(System.nanoTime() + 1_000_000)
    .build()

  val scopeSpans = ScopeSpans
    .newBuilder()
    .addSpans(span)
    .build()

  val resourceSpans = ResourceSpans
    .newBuilder()
    .setResource(resource)
    .addScopeSpans(scopeSpans)
    .build()

  return ExportTraceServiceRequest
    .newBuilder()
    .addResourceSpans(resourceSpans)
    .build()
}

private fun createExportRequestWithMultipleSpans(
  serviceName: String,
  traceId: String,
  spanNames: List<String>
): ExportTraceServiceRequest {
  val resource = Resource
    .newBuilder()
    .addAttributes(
      KeyValue
        .newBuilder()
        .setKey("service.name")
        .setValue(AnyValue.newBuilder().setStringValue(serviceName))
    ).build()

  val scopeSpansBuilder = ScopeSpans.newBuilder()

  spanNames.forEachIndexed { index, name ->
    val span = Span
      .newBuilder()
      .setTraceId(hexStringToByteString(traceId))
      .setSpanId(hexStringToByteString("${index + 1}".padStart(16, '0')))
      .setName(name)
      .setStartTimeUnixNano(System.nanoTime())
      .setEndTimeUnixNano(System.nanoTime() + 1_000_000)
      .build()
    scopeSpansBuilder.addSpans(span)
  }

  val resourceSpans = ResourceSpans
    .newBuilder()
    .setResource(resource)
    .addScopeSpans(scopeSpansBuilder.build())
    .build()

  return ExportTraceServiceRequest
    .newBuilder()
    .addResourceSpans(resourceSpans)
    .build()
}

private fun createExportRequestWithErrorSpan(
  serviceName: String,
  traceId: String,
  spanId: String,
  spanName: String,
  errorMessage: String
): ExportTraceServiceRequest {
  val resource = Resource
    .newBuilder()
    .addAttributes(
      KeyValue
        .newBuilder()
        .setKey("service.name")
        .setValue(AnyValue.newBuilder().setStringValue(serviceName))
    ).build()

  val span = Span
    .newBuilder()
    .setTraceId(hexStringToByteString(traceId))
    .setSpanId(hexStringToByteString(spanId))
    .setName(spanName)
    .setStartTimeUnixNano(System.nanoTime())
    .setEndTimeUnixNano(System.nanoTime() + 1_000_000)
    .setStatus(
      Status
        .newBuilder()
        .setCodeValue(TracingConstants.OTEL_STATUS_CODE_ERROR)
        .setMessage(errorMessage)
    ).build()

  val scopeSpans = ScopeSpans
    .newBuilder()
    .addSpans(span)
    .build()

  val resourceSpans = ResourceSpans
    .newBuilder()
    .setResource(resource)
    .addScopeSpans(scopeSpans)
    .build()

  return ExportTraceServiceRequest
    .newBuilder()
    .addResourceSpans(resourceSpans)
    .build()
}

private fun createExportRequestWithAttributes(
  serviceName: String,
  traceId: String,
  spanId: String,
  spanName: String,
  attributes: Map<String, String>
): ExportTraceServiceRequest {
  val resource = Resource
    .newBuilder()
    .addAttributes(
      KeyValue
        .newBuilder()
        .setKey("service.name")
        .setValue(AnyValue.newBuilder().setStringValue(serviceName))
    ).build()

  val spanBuilder = Span
    .newBuilder()
    .setTraceId(hexStringToByteString(traceId))
    .setSpanId(hexStringToByteString(spanId))
    .setName(spanName)
    .setStartTimeUnixNano(System.nanoTime())
    .setEndTimeUnixNano(System.nanoTime() + 1_000_000)

  attributes.forEach { (key, value) ->
    spanBuilder.addAttributes(
      KeyValue
        .newBuilder()
        .setKey(key)
        .setValue(AnyValue.newBuilder().setStringValue(value))
    )
  }

  val scopeSpans = ScopeSpans
    .newBuilder()
    .addSpans(spanBuilder.build())
    .build()

  val resourceSpans = ResourceSpans
    .newBuilder()
    .setResource(resource)
    .addScopeSpans(scopeSpans)
    .build()

  return ExportTraceServiceRequest
    .newBuilder()
    .addResourceSpans(resourceSpans)
    .build()
}

private fun createExportRequestWithExceptionEvent(
  serviceName: String,
  traceId: String,
  spanId: String,
  spanName: String,
  exceptionType: String,
  exceptionMessage: String,
  exceptionStacktrace: String
): ExportTraceServiceRequest {
  val resource = Resource
    .newBuilder()
    .addAttributes(
      KeyValue
        .newBuilder()
        .setKey("service.name")
        .setValue(AnyValue.newBuilder().setStringValue(serviceName))
    ).build()

  val exceptionEvent = Span.Event
    .newBuilder()
    .setName(TracingConstants.OTEL_EXCEPTION_EVENT_NAME)
    .setTimeUnixNano(System.nanoTime())
    .addAttributes(
      KeyValue
        .newBuilder()
        .setKey(TracingConstants.OTEL_EXCEPTION_TYPE_ATTRIBUTE)
        .setValue(AnyValue.newBuilder().setStringValue(exceptionType))
    ).addAttributes(
      KeyValue
        .newBuilder()
        .setKey(TracingConstants.OTEL_EXCEPTION_MESSAGE_ATTRIBUTE)
        .setValue(AnyValue.newBuilder().setStringValue(exceptionMessage))
    ).addAttributes(
      KeyValue
        .newBuilder()
        .setKey(TracingConstants.OTEL_EXCEPTION_STACKTRACE_ATTRIBUTE)
        .setValue(AnyValue.newBuilder().setStringValue(exceptionStacktrace))
    ).build()

  val span = Span
    .newBuilder()
    .setTraceId(hexStringToByteString(traceId))
    .setSpanId(hexStringToByteString(spanId))
    .setName(spanName)
    .setStartTimeUnixNano(System.nanoTime())
    .setEndTimeUnixNano(System.nanoTime() + 1_000_000)
    .setStatus(
      Status
        .newBuilder()
        .setCodeValue(TracingConstants.OTEL_STATUS_CODE_ERROR)
        .setMessage(exceptionMessage)
    ).addEvents(exceptionEvent)
    .build()

  val scopeSpans = ScopeSpans
    .newBuilder()
    .addSpans(span)
    .build()

  val resourceSpans = ResourceSpans
    .newBuilder()
    .setResource(resource)
    .addScopeSpans(scopeSpans)
    .build()

  return ExportTraceServiceRequest
    .newBuilder()
    .addResourceSpans(resourceSpans)
    .build()
}
