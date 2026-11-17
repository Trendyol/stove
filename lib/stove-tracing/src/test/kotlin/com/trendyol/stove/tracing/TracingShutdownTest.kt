package com.trendyol.stove.tracing

import com.trendyol.stove.system.PortFinder
import com.trendyol.stove.system.Stove
import io.grpc.ManagedChannelBuilder
import io.grpc.StatusRuntimeException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest
import io.opentelemetry.proto.collector.trace.v1.TraceServiceGrpc
import java.util.concurrent.TimeUnit

class TracingShutdownTest : FunSpec({
  test("closing the tracing system stops its OTLP receiver before event detachment") {
    Stove().use { stove ->
      val port = PortFinder.findAvailablePort()
      val tracing = TracingSystem(stove, TracingSystemOptions(TracingOptions().enableSpanReceiver(port)))
      val channel = ManagedChannelBuilder.forAddress("localhost", port).usePlaintext().build()
      try {
        tracing.run()
        val client = TraceServiceGrpc.newBlockingStub(channel).withDeadlineAfter(2, TimeUnit.SECONDS)
        client.export(ExportTraceServiceRequest.getDefaultInstance())
        tracing.close()
        shouldThrow<StatusRuntimeException> { client.export(ExportTraceServiceRequest.getDefaultInstance()) }
      } finally {
        tracing.close()
        channel.shutdownNow()
      }
    }
  }
})
