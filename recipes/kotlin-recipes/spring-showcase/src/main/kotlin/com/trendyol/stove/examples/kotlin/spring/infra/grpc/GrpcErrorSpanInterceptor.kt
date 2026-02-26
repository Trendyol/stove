package com.trendyol.stove.examples.kotlin.spring.infra.grpc

import io.grpc.Context
import io.grpc.Contexts
import io.grpc.ForwardingServerCall
import io.grpc.Metadata
import io.grpc.ServerCall
import io.grpc.ServerCallHandler
import io.grpc.ServerInterceptor
import io.grpc.Status
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.StatusCode
import org.springframework.stereotype.Component

/**
 * gRPC server interceptor that records errors on the current OpenTelemetry span.
 *
 * When any gRPC call fails (non-OK status), the error is recorded on the span
 * so it appears in traces for debugging and observability.
 */
@Component
class GrpcErrorSpanInterceptor : ServerInterceptor {
  override fun <ReqT, RespT> interceptCall(
    call: ServerCall<ReqT, RespT>,
    headers: Metadata,
    next: ServerCallHandler<ReqT, RespT>
  ): ServerCall.Listener<ReqT> {
    val wrappedCall = object : ForwardingServerCall.SimpleForwardingServerCall<ReqT, RespT>(call) {
      override fun close(status: Status, trailers: Metadata) {
        if (!status.isOk) {
          Span.current().apply {
            recordException(status.asRuntimeException())
            setStatus(StatusCode.ERROR, status.description ?: status.code.name)
          }
        }
        super.close(status, trailers)
      }
    }
    return Contexts.interceptCall(
      Context.current(),
      wrappedCall,
      headers,
      next
    )
  }
}
