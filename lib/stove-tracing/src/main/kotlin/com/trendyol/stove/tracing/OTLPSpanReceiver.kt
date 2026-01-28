package com.trendyol.stove.tracing

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.google.protobuf.ByteString
import io.grpc.Server
import io.grpc.ServerBuilder
import io.grpc.Status
import io.grpc.stub.StreamObserver
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceResponse
import io.opentelemetry.proto.collector.trace.v1.TraceServiceGrpc
import io.opentelemetry.proto.trace.v1.ResourceSpans
import io.opentelemetry.proto.trace.v1.ScopeSpans
import org.slf4j.LoggerFactory
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * OTLP-compatible span receiver that collects spans from the application under test via gRPC.
 *
 * This uses gRPC protocol which is the default for OpenTelemetry Java Agent and avoids
 * classloader isolation issues since the agent communicates via protocol rather than shared classes.
 *
 * Example app configuration (OpenTelemetry Java Agent):
 * ```
 * -Dotel.traces.exporter=otlp
 * -Dotel.exporter.otlp.protocol=grpc
 * -Dotel.exporter.otlp.endpoint=http://localhost:4317
 * -Dotel.service.name=my-service
 * ```
 *
 * Inspired by TestSpanCollector from beholder-otel-extension.
 */
class OTLPSpanReceiver(
  private val collector: StoveTraceCollector,
  private val port: Int = TracingConstants.DEFAULT_OTLP_GRPC_PORT
) {
  private val logger = LoggerFactory.getLogger(OTLPSpanReceiver::class.java)
  private val lock = ReentrantLock()
  private var server: Server? = null

  @Volatile private var running = false

  val endpoint: String get() = "http://localhost:$port"

  fun start(): Either<OTLPReceiverError, Unit> = lock.withLock {
    try {
      if (running) {
        return Unit.right()
      }

      server = ServerBuilder
        .forPort(port)
        .addService(TraceServiceImpl(collector, logger))
        .build()
        .start()

      running = true
      logger.info("[StoveOtlp] Started OTLP gRPC collector on port {}", port)
      Unit.right()
    } catch (e: IOException) {
      OTLPReceiverError.StartupFailed(port, e).left()
    }
  }

  fun stop(): Unit = lock.withLock {
    if (!running || server == null) {
      return
    }

    try {
      server?.shutdown()
      val terminated = server?.awaitTermination(
        TracingConstants.SERVER_SHUTDOWN_TIMEOUT_SECONDS,
        TimeUnit.SECONDS
      ) ?: false

      if (!terminated) {
        server?.shutdownNow()
      }
    } catch (_: InterruptedException) {
      server?.shutdownNow()
      Thread.currentThread().interrupt()
    }

    running = false
    logger.info("[StoveOtlp] Stopped OTLP gRPC collector")
  }
}

/**
 * gRPC service implementation that receives OTLP trace data.
 */
private class TraceServiceImpl(
  private val collector: StoveTraceCollector,
  private val logger: org.slf4j.Logger
) : TraceServiceGrpc.TraceServiceImplBase() {
  override fun export(
    request: ExportTraceServiceRequest,
    responseObserver: StreamObserver<ExportTraceServiceResponse>
  ) {
    try {
      val spans = extractSpansFromRequest(request)
      spans.forEach { collector.record(it) }

      if (spans.isNotEmpty()) {
        logger.info(
          "[StoveOtlp] Received {} spans from service '{}'",
          spans.size,
          spans.firstOrNull()?.serviceName ?: "unknown"
        )
      }
      logger.debug("[StoveOtlp] Processed {} spans total", spans.size)

      responseObserver.onNext(ExportTraceServiceResponse.getDefaultInstance())
      responseObserver.onCompleted()
    } catch (e: IOException) {
      logger.error("[StoveOtlp] IO error processing spans", e)
      responseObserver.onError(
        Status.INTERNAL
          .withDescription("Failed to process spans: ${e.message}")
          .withCause(e)
          .asException()
      )
    } catch (e: IllegalArgumentException) {
      logger.warn("[StoveOtlp] Invalid span data received", e)
      responseObserver.onError(
        Status.INVALID_ARGUMENT
          .withDescription("Invalid span data: ${e.message}")
          .withCause(e)
          .asException()
      )
    } catch (e: IllegalStateException) {
      logger.warn("[StoveOtlp] Unexpected state during span processing", e)
      responseObserver.onError(
        Status.FAILED_PRECONDITION
          .withDescription("Unexpected state: ${e.message}")
          .withCause(e)
          .asException()
      )
    }
  }

  private fun extractSpansFromRequest(request: ExportTraceServiceRequest): List<SpanInfo> =
    request.resourceSpansList
      .flatMap { resourceSpans -> extractSpansFromResource(resourceSpans) }
      .filterNot { isInternalGrpcSpan(it.operationName) }

  private fun extractSpansFromResource(resourceSpans: ResourceSpans): List<SpanInfo> {
    val serviceName = extractServiceName(resourceSpans)
    return resourceSpans.scopeSpansList
      .flatMap { scopeSpans -> extractSpansFromScope(scopeSpans, serviceName) }
  }

  private fun extractServiceName(resourceSpans: ResourceSpans): String =
    resourceSpans.resource
      ?.attributesList
      ?.find { it.key == TracingConstants.OTEL_SERVICE_NAME_ATTRIBUTE }
      ?.value
      ?.stringValue
      ?: "unknown"

  private fun extractSpansFromScope(scopeSpans: ScopeSpans, serviceName: String): List<SpanInfo> =
    scopeSpans.spansList.map { span ->
      SpanInfo(
        traceId = span.traceId.toHex(),
        spanId = span.spanId.toHex(),
        parentSpanId = if (span.parentSpanId.isEmpty) null else span.parentSpanId.toHex(),
        operationName = span.name,
        serviceName = serviceName,
        startTimeNanos = span.startTimeUnixNano,
        endTimeNanos = span.endTimeUnixNano,
        status = when (span.status.codeValue) {
          TracingConstants.OTEL_STATUS_CODE_ERROR -> SpanStatus.ERROR
          else -> SpanStatus.OK
        },
        attributes = span.attributesList.associate { kv ->
          kv.key to extractAttributeValue(kv.value)
        },
        exception = extractExceptionFromSpan(span)
      )
    }

  private fun extractExceptionFromSpan(
    span: io.opentelemetry.proto.trace.v1.Span
  ): ExceptionInfo? {
    // First try to extract exception from events (preferred - contains full details)
    val exceptionEvent = span.eventsList.find { event ->
      event.name == TracingConstants.OTEL_EXCEPTION_EVENT_NAME
    }

    if (exceptionEvent != null) {
      val attributes = exceptionEvent.attributesList.associate { kv ->
        kv.key to extractAttributeValue(kv.value)
      }
      val exceptionType = attributes[TracingConstants.OTEL_EXCEPTION_TYPE_ATTRIBUTE] ?: "Exception"
      val exceptionMessage = attributes[TracingConstants.OTEL_EXCEPTION_MESSAGE_ATTRIBUTE] ?: ""
      val stackTrace = attributes[TracingConstants.OTEL_EXCEPTION_STACKTRACE_ATTRIBUTE]
        ?.split("\n")
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        ?: emptyList()

      return ExceptionInfo(exceptionType, exceptionMessage, stackTrace)
    }

    // Fallback to status message if error status but no exception event
    if (span.status.codeValue == TracingConstants.OTEL_STATUS_CODE_ERROR &&
      span.status.message.isNotEmpty()
    ) {
      return ExceptionInfo("Error", span.status.message, emptyList())
    }

    return null
  }

  private fun extractAttributeValue(value: io.opentelemetry.proto.common.v1.AnyValue): String = when {
    value.hasStringValue() -> value.stringValue
    value.hasIntValue() -> value.intValue.toString()
    value.hasBoolValue() -> value.boolValue.toString()
    value.hasDoubleValue() -> value.doubleValue.toString()
    else -> ""
  }

  private fun isInternalGrpcSpan(spanName: String): Boolean =
    TracingConstants.GRPC_INTERNAL_SPAN_PATTERNS.any { pattern ->
      spanName.contains(pattern)
    }

  private fun ByteString.toHex(): String {
    if (isEmpty) return ""
    return toByteArray().joinToString("") { "%02x".format(it) }
  }
}

/**
 * Errors that can occur during OTLP receiver operations.
 */
sealed class OTLPReceiverError(
  message: String,
  cause: Throwable? = null
) : Exception(message, cause) {
  data class StartupFailed(
    val port: Int,
    override val cause: IOException
  ) : OTLPReceiverError("Failed to start OTLP gRPC collector on port $port", cause)
}
