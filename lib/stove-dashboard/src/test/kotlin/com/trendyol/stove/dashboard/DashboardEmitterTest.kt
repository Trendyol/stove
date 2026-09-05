package com.trendyol.stove.dashboard

import com.trendyol.stove.dashboard.api.*
import com.trendyol.stove.dashboard.api.DashboardEventServiceGrpcKt.DashboardEventServiceCoroutineImplBase
import io.grpc.*
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds

class DashboardEmitterTest :
  FunSpec({

    test("emits events to a running gRPC server") {
      val received = CopyOnWriteArrayList<DashboardEvent>()
      val server = startMockServer(received, port = 0)
      val port = server.port

      try {
        val emitter = isolatedEmitter(DashboardIngestion.Grpc(port = port))
        val event = DashboardEvent.newBuilder()
          .setRunId("run-1")
          .setRunStarted(
            RunStartedEvent.newBuilder()
              .setAppName("test-app")
              .build()
          )
          .build()

        emitter.tryEmit(event)
        emitter.tryEmit(event)

        // Wait for async drain
        delay(500.milliseconds)
        emitter.close()

        received.size shouldBe 2
        received[0].runId shouldBe "run-1"
        received.map { it.sequence } shouldBe listOf(1L, 2L)
        received.map { it.eventId }.toSet().size shouldBe 2
      } finally {
        server.shutdownNow()
      }
    }

    test("retains events when shutdown cannot reach the server") {
      // Connect to a port that is not listening
      val emitter = isolatedEmitter(DashboardIngestion.Grpc(port = 1), maxFailures = 2)

      // These should not throw
      repeat(10) {
        emitter.tryEmit(
          DashboardEvent.newBuilder()
            .setRunId("run-1")
            .setRunStarted(RunStartedEvent.newBuilder().setAppName("test").build())
            .build()
        )
      }

      // Wait for the drain loop to process and fail
      delay(2000.milliseconds)
      emitter.close()

      // If we get here without exception, the test passes
    }

    test("does not drop burst events while receiver is temporarily blocked") {
      val received = CopyOnWriteArrayList<DashboardEvent>()
      val firstRequestStarted = CountDownLatch(1)
      val releaseFirstRequest = CountDownLatch(1)
      val server = startMockServer(received, port = 0) {
        if (firstRequestStarted.count > 0) {
          firstRequestStarted.countDown()
          releaseFirstRequest.await(5, TimeUnit.SECONDS)
        }
      }
      val port = server.port

      try {
        val emitter = isolatedEmitter(DashboardIngestion.Grpc(port = port))
        val totalEvents = 700

        repeat(totalEvents) { index ->
          emitter.tryEmit(runStartedEvent(index))
        }

        firstRequestStarted.await(2, TimeUnit.SECONDS) shouldBe true
        releaseFirstRequest.countDown()

        delay(500.milliseconds)
        emitter.close()

        received.size shouldBe totalEvents
      } finally {
        server.shutdownNow()
      }
    }

    test("assigns per-run identities in queue order under concurrent producers") {
      val received = CopyOnWriteArrayList<DashboardEvent>()
      val server = startMockServer(received, port = 0)

      try {
        val emitter = isolatedEmitter(DashboardIngestion.Grpc(port = server.port))
        val event = runStartedEvent(1).toBuilder().setRunId("shared-run").build()
        val producers = List(100) { Thread { emitter.tryEmit(event) } }

        producers.forEach(Thread::start)
        producers.forEach(Thread::join)
        emitter.close()

        received.map { it.sequence } shouldBe (1L..100L).toList()
        received.map { it.eventId }.toSet().size shouldBe 100
      } finally {
        server.shutdownNow()
      }
    }

    test("surfaces permanent rejection and retains the rejected evidence") {
      val received = CopyOnWriteArrayList<DashboardEvent>()
      val server = startMockServer(received, port = 0) {
        throw StatusException(Status.INVALID_ARGUMENT.withDescription("invalid event"))
      }
      val emitter = isolatedEmitter(DashboardIngestion.Grpc(port = server.port))
      try {
        emitter.tryEmit(runStartedEvent(1))
        io.kotest.assertions.throwables.shouldThrow<DashboardSpoolException> { emitter.close() }
        emitter.deliveryStatus.pendingEvents shouldBe 1
      } finally {
        server.shutdownNow()
      }
    }

    test("continues retries and retains events after UNAVAILABLE during shutdown") {
      val attempts = AtomicInteger(0)
      val received = CopyOnWriteArrayList<DashboardEvent>()
      val server = startMockServer(received, port = 0) {
        attempts.incrementAndGet()
        throw StatusException(Status.UNAVAILABLE.withDescription("server down"))
      }
      val port = server.port

      try {
        val emitter = isolatedEmitter(DashboardIngestion.Grpc(port = port), maxFailures = 3)

        repeat(10) { index -> emitter.tryEmit(runStartedEvent(index)) }

        delay(1500.milliseconds)
        emitter.close()

        // Retrying stops only at shutdown; the unacknowledged events remain durable.
        (attempts.get() >= 3) shouldBe true
        emitter.deliveryStatus.pendingEvents shouldBe 10
      } finally {
        server.shutdownNow()
      }
    }

    test("close drains queued events before shutting down") {
      val received = CopyOnWriteArrayList<DashboardEvent>()
      val server = startMockServer(received, port = 0) {
        delay(12.milliseconds)
      }
      val port = server.port

      try {
        val emitter = isolatedEmitter(DashboardIngestion.Grpc(port = port))
        val totalEvents = 350

        repeat(totalEvents) { index ->
          emitter.tryEmit(runStartedEvent(index))
        }

        emitter.close()

        received.size shouldBe totalEvents
      } finally {
        server.shutdownNow()
      }
    }

    test("close does not abandon the terminal event when draining takes longer than the warning interval") {
      val received = CopyOnWriteArrayList<DashboardEvent>()
      val server = startMockServer(received, port = 0) {
        delay(40.milliseconds)
      }

      try {
        val emitter = isolatedEmitter(
          DashboardIngestion.Grpc(port = server.port),
          maxFailures = 5,
          drainWarningIntervalMs = 25
        )
        emitter.tryEmit(runStartedEvent(1))
        emitter.tryEmit(runStartedEvent(2))
        emitter.tryEmit(
          DashboardEvent.newBuilder()
            .setRunId("run-2")
            .setRunEnded(RunEndedEvent.getDefaultInstance())
            .build()
        )

        emitter.close()

        received.size shouldBe 3
        received.last().hasRunEnded() shouldBe true
      } finally {
        server.shutdownNow()
      }
    }
  })

private fun startMockServer(
  received: MutableList<DashboardEvent>,
  port: Int,
  beforeAck: suspend (DashboardEvent) -> Unit = {}
): Server {
  val service = object : DashboardEventServiceCoroutineImplBase() {
    override suspend fun sendEvent(request: DashboardEvent): EventAck {
      beforeAck(request)
      received.add(request)
      return EventAck.newBuilder().setAccepted(true).build()
    }

    override suspend fun streamEvents(requests: Flow<DashboardEvent>): EventAck {
      requests.collect { received.add(it) }
      return EventAck.newBuilder().setAccepted(true).build()
    }
  }

  return ServerBuilder.forPort(port)
    .addService(service)
    .build()
    .start()
}

private fun runStartedEvent(index: Int): DashboardEvent =
  DashboardEvent.newBuilder()
    .setRunId("run-$index")
    .setRunStarted(
      RunStartedEvent.newBuilder()
        .setAppName("test-app")
        .build()
    )
    .build()
