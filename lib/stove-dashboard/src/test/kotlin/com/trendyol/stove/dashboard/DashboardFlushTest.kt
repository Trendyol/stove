package com.trendyol.stove.dashboard

import com.trendyol.stove.dashboard.api.*
import io.grpc.ServerBuilder
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.comparables.shouldBeLessThan
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime

class DashboardFlushTest : FunSpec({
  test("close bounds the flush when the receiver never acknowledges") {
    val receiver = UnresponsiveReceiver()
    val server = ServerBuilder.forPort(0).addService(receiver).build().start()
    val emitter = DashboardEmitter(DashboardIngestion.Grpc(port = server.port), 5, 25, 100)
    try {
      emitter.tryEmit(DashboardEvent.newBuilder().setRunId("run-1").build())
      withTimeout(5.seconds) { receiver.started.await() }
      measureTime { emitter.close() } shouldBeLessThan 3.seconds
      withTimeout(5.seconds) { receiver.cancelled.await() }
    } finally {
      emitter.close()
      server.shutdownNow()
    }
  }
})

private class UnresponsiveReceiver : DashboardEventServiceGrpcKt.DashboardEventServiceCoroutineImplBase() {
  val started = CompletableDeferred<Unit>()
  val cancelled = CompletableDeferred<Unit>()
  override suspend fun sendEvent(request: DashboardEvent): EventAck {
    started.complete(Unit)
    try {
      awaitCancellation()
    } finally {
      cancelled.complete(Unit)
    }
  }
}
