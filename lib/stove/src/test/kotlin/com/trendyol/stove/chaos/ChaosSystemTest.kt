package com.trendyol.stove.chaos

import com.trendyol.stove.system.Stove
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.*
import kotlin.time.Duration.Companion.milliseconds

class ChaosSystemTest : FunSpec({
  test("disabled routing preserves the original configuration and rejects faults") {
    val system = ChaosTestSystem(Stove())
    val direct = TcpEndpoint("localhost", 1234)
    system.applicationConfiguration(direct, IdentityEndpointAdapter) shouldBe direct
    shouldThrow<IllegalStateException> {
      withSystemValidation(system) { duringFault { error("must not execute") } }
    }.message shouldBe "Chaos is not enabled for this system; call enableChaos() in its setup block"
    system.stove.close()
  }

  test("requires the matching system validation scope and a started route") {
    ChaosFixture().use { fixture ->
      shouldThrow<IllegalStateException> { fixture.system.duringFault {} }
      shouldThrow<IllegalStateException> {
        withSystemValidation(fixture.system) { duringFault {} }
      }.message shouldBe "Chaos route ChaosTestSystem has not been started"
      fixture.start()
      withSystemValidation(ChaosTestSystem(fixture.stove)) {
        shouldThrow<IllegalStateException> { fixture.system.duringFault {} }
      }
    }
  }

  test("fault body executes once and heals before recovery assertions") {
    ChaosFixture().use { fixture ->
      fixture.start()
      var executions = 0
      withSystemValidation(fixture.system) {
        duringFault(latency(20.milliseconds)) {
          executions++
          fixture.controller.active shouldBe true
        }
        fixture.controller.active shouldBe false
        duringFault(stall()) { executions++ }
      }
      executions shouldBe 2
      fixture.controller.heals shouldBe 2
    }
  }

  test("equal system instances keep independent routes and fault reservations") {
    ChaosFixture().use { fixture ->
      val secondSystem = fixture.addSystem()
      secondSystem shouldBe fixture.system

      fixture.start() shouldBe TcpEndpoint("localhost", 4321)
      fixture.start(secondSystem) shouldBe TcpEndpoint("localhost", 4322)

      coroutineScope {
        val firstFault = launchActiveFault(fixture.system)
        withSystemValidation(secondSystem) {
          duringFault {
            fixture.controllers.map { it.active } shouldBe listOf(true, true)
          }
        }
        fixture.controllers.map { it.active } shouldBe listOf(true, false)
        firstFault.cancelAndJoin()
      }
      fixture.controllers.map { it.heals } shouldBe listOf(1, 1)
    }
  }

  test("enabling the same system again preserves its route and active reservation") {
    ChaosFixture().use { fixture ->
      val endpoint = fixture.start()
      withSystemValidation(fixture.system) {
        duringFault {
          fixture.system.enableChaosRouting()
          fixture.start() shouldBe endpoint
          fixture.controller.active shouldBe true
        }
      }

      fixture.controller.heals shouldBe 1
      fixture.close()
      fixture.closedEndpoints shouldBe listOf(endpoint)
    }
  }

  test("shutdown closes routes once in reverse registration order") {
    ChaosFixture().use { fixture ->
      val first = fixture.start()
      val second = fixture.start(fixture.addSystem())
      val third = fixture.start(fixture.addSystem())

      fixture.close()
      fixture.close()

      fixture.closedEndpoints shouldBe listOf(third, second, first)
    }
  }

  test("a partially applied fault is restored and preserves the activation error") {
    ChaosFixture().use { fixture ->
      fixture.start()
      val failure = IllegalArgumentException("activation failed after mutation")
      fixture.controller.applyFailure = failure
      shouldThrow<IllegalArgumentException> {
        withSystemValidation(fixture.system) { duringFault { error("must not execute") } }
      } shouldBe failure
      fixture.controller.active shouldBe false
      fixture.controller.heals shouldBe 1
    }
  }

  test("failed restoration is suppressed and quarantines the route") {
    ChaosFixture().use { fixture ->
      fixture.start()
      val primary = IllegalArgumentException("assertion failed")
      val cleanup = IllegalStateException("cannot heal")
      fixture.controller.healFailure = cleanup
      shouldThrow<IllegalArgumentException> {
        withSystemValidation(fixture.system) { duringFault { throw primary } }
      } shouldBe primary
      primary.suppressed.toList() shouldBe listOf(cleanup)
      shouldThrow<IllegalStateException> {
        withSystemValidation(fixture.system) { duringFault {} }
      }.message shouldBe "Chaos route ChaosTestSystem is unavailable; recreate the fixture"
      fixture.controller.healFailure = null
    }
  }

  test("cancellation restores connectivity and releases the reservation") {
    ChaosFixture().use { fixture ->
      fixture.start()
      coroutineScope {
        val owner = launchActiveFault(fixture.system)
        owner.cancelAndJoin()
      }
      fixture.controller.active shouldBe false
      withSystemValidation(fixture.system) { duringFault {} }
    }
  }

  test("rejects nesting and holds the reservation throughout recovery") {
    ChaosFixture().use { fixture ->
      fixture.start()
      coroutineScope {
        val recovery = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val owner = launch {
          withSystemValidation(fixture.system) {
            duringFault {
              shouldThrow<IllegalStateException> { fixture.system.duringFault {} }
            }
            recovery.complete(Unit)
            release.await()
          }
        }
        recovery.await()
        shouldThrow<IllegalStateException> {
          withSystemValidation(fixture.system) { duringFault {} }
        }.message shouldBe "Chaos route ChaosTestSystem is reserved by another validation block"
        release.complete(Unit)
        owner.join()
      }
      withSystemValidation(fixture.system) { duringFault {} }
    }
  }

  test("shutdown cancels an active fault and restores before closing its route") {
    ChaosFixture().use { fixture ->
      fixture.start()
      coroutineScope {
        val owner = launchActiveFault(fixture.system)
        fixture.coordinator.beforeApplicationStop()
        owner.isCompleted shouldBe true
        fixture.controller.active shouldBe false
        fixture.controller.heals shouldBe 1
      }
      shouldThrow<IllegalStateException> {
        withSystemValidation(fixture.system) { duringFault {} }
      }
    }
  }
})

private data class ChaosTestSystem(override val stove: Stove) : ChaosCapableSystem {
  override fun close() = Unit
}

private object IdentityEndpointAdapter : ChaosEndpointAdapter<TcpEndpoint> {
  override fun endpoint(configuration: TcpEndpoint): TcpEndpoint = configuration
  override fun applicationConfiguration(direct: TcpEndpoint, endpoint: TcpEndpoint): TcpEndpoint = endpoint
}

private class ChaosFixture : AutoCloseable {
  val stove = Stove()
  val controllers = mutableListOf<RecordingFaultController>()
  val closedEndpoints = mutableListOf<TcpEndpoint>()
  val coordinator = stove.getOrRegister(
    ChaosSystem(stove) {
      RecordingFaultController(TcpEndpoint("localhost", 4321 + controllers.size)) { endpoint ->
        closedEndpoints += endpoint
      }.also { controllers += it }
    }
  )
  val system = addSystem()
  val controller: RecordingFaultController get() = controllers.first()

  fun addSystem(): ChaosTestSystem = ChaosTestSystem(stove).also { it.enableChaosRouting() }

  fun start(system: ChaosTestSystem = this.system): TcpEndpoint =
    system.applicationConfiguration(TcpEndpoint("localhost", 1234), IdentityEndpointAdapter)

  override fun close() = stove.close()
}

private suspend fun CoroutineScope.launchActiveFault(system: ChaosTestSystem): Job {
  val entered = CompletableDeferred<Unit>()
  val owner = launch {
    withSystemValidation(system) {
      duringFault {
        entered.complete(Unit)
        awaitCancellation()
      }
    }
  }
  entered.await()
  return owner
}

private class RecordingFaultController(
  private val endpoint: TcpEndpoint,
  private val onClose: (TcpEndpoint) -> Unit
) : FaultController {
  var active = false
  var heals = 0
  var applyFailure: Throwable? = null
  var healFailure: Throwable? = null

  override fun start(target: TcpEndpoint): TcpEndpoint = endpoint
  override fun apply(fault: NetworkFault) {
    active = true
    applyFailure?.let { throw it }
  }
  override fun heal() {
    heals++
    healFailure?.let { throw it }
    active = false
  }
  override fun close() = onClose(endpoint)
}
