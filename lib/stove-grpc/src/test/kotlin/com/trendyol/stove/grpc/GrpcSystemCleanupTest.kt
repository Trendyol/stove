package com.trendyol.stove.grpc

import com.trendyol.stove.system.Stove
import io.grpc.CallOptions
import io.grpc.ClientCall
import io.grpc.ManagedChannel
import io.grpc.MethodDescriptor
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

class GrpcSystemCleanupTest : FunSpec({
  test("closing an unused system does not allocate either client") {
    val system = GrpcSystem(
      Stove(),
      GrpcSystemOptions(
        host = "localhost",
        port = 1,
        createChannel = { _, _ -> error("channel must remain uninitialized") },
        createWireClient = { _, _ -> error("Wire client must remain uninitialized") }
      )
    )
    system.close()
  }

  test("Wire resources close even when the managed channel fails to shut down") {
    val failure = IllegalStateException("channel shutdown failed")
    val fixture = GrpcCleanupFixture(failure)

    shouldThrow<IllegalStateException> { fixture.system.close() } shouldBe failure
    fixture.wireExecutorStopped shouldBe true
  }
})

private class GrpcCleanupFixture(failure: RuntimeException) {
  private val wire = defaultWireGrpcClient("localhost", 1, 1.seconds, emptyMap())
  val system = GrpcSystem(
    Stove(),
    GrpcSystemOptions(
      host = "localhost",
      port = 1,
      createChannel = { _, _ -> FailingShutdownChannel(failure) },
      createWireClient = { _, _ -> wire }
    )
  ).apply {
    // Initialize both owned resources without making an RPC.
    grpcChannel
    wireClientResources
  }
  val wireExecutorStopped: Boolean get() = wire.okHttpClient.dispatcher.executorService.isShutdown
}

private class FailingShutdownChannel(private val failure: RuntimeException) : ManagedChannel() {
  override fun shutdown(): ManagedChannel = throw failure
  override fun shutdownNow(): ManagedChannel = shutdown()
  override fun isShutdown(): Boolean = false
  override fun isTerminated(): Boolean = false
  override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = false
  override fun authority(): String = "localhost"
  override fun <ReqT, RespT> newCall(
    methodDescriptor: MethodDescriptor<ReqT, RespT>,
    callOptions: CallOptions
  ): ClientCall<ReqT, RespT> = error("This fixture does not make RPCs")
}
