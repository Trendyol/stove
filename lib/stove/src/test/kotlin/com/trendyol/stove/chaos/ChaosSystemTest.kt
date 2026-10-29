package com.trendyol.stove.chaos

import com.trendyol.stove.system.Stove
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

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

  test("all chaos operations reject a disabled target consistently when another route is enabled") {
    ChaosFixture().use { fixture ->
      val disabled = ChaosTestSystem(fixture.stove)
      val expected = "Chaos is not enabled for this system; call enableChaos() in its setup block"
      withSystemValidation(disabled) {
        shouldThrow<IllegalStateException> { duringFault {} }.message shouldBe expected
        shouldThrow<IllegalStateException> {
          chaosExperiment("disabled", stall(), 1.seconds, {}, {}, {})
        }.message shouldBe expected
        shouldThrow<IllegalStateException> {
          awaitStalledTraffic(TrafficDirection.Requests, 1.seconds)
        }.message shouldBe expected
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

  test("experiment reserves before baseline and runs each phase once with healing before recovery") {
    ChaosFixture().use { fixture ->
      fixture.start()
      val phases = mutableListOf<String>()
      withSystemValidation(fixture.system) {
        chaosExperiment(
          "recovery",
          stall(),
          2.seconds,
          baseline = {
            phases += "baseline"
            fixture.controller.active shouldBe false
            shouldThrow<IllegalStateException> {
              fixture.system.duringFault { error("must not run") }
            }.message shouldBe "Standalone faults on ChaosTestSystem are not supported while an experiment is running"
            shouldThrow<IllegalStateException> {
              fixture.system.chaosExperiment("nested", stall(), 1.seconds, {}, {}, {})
            }
            shouldThrow<IllegalStateException> {
              fixture.system.awaitStalledTraffic(TrafficDirection.Responses, 1.seconds)
            }
            shouldThrow<IllegalStateException> {
              withSystemValidation(fixture.system) { duringFault {} }
            }
          },
          faulted = {
            phases += "fault"
            fixture.controller.active shouldBe true
          },
          recovery = {
            phases += "recovery"
            fixture.controller.active shouldBe false
            shouldThrow<IllegalStateException> {
              fixture.system.duringFault { error("must not run") }
            }.message shouldBe "Standalone faults on ChaosTestSystem are not supported while an experiment is running"
            shouldThrow<IllegalStateException> {
              withSystemValidation(fixture.system) { duringFault {} }
            }
          }
        )
      }
      phases shouldBe listOf("baseline", "fault", "recovery")
      fixture.controller.heals shouldBe 1
      withSystemValidation(fixture.system) { duringFault {} }
    }
  }

  test("each experiment phase waits for its children before advancing or healing") {
    ChaosFixture().use { fixture ->
      fixture.start()
      coroutineScope {
        val baselineStarted = CompletableDeferred<Unit>()
        val releaseBaseline = CompletableDeferred<Unit>()
        val faultStarted = CompletableDeferred<Unit>()
        val releaseFault = CompletableDeferred<Unit>()
        val recoveryStarted = CompletableDeferred<Unit>()
        val releaseRecovery = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()
        val experiment = launch(start = CoroutineStart.UNDISPATCHED) {
          withSystemValidation(fixture.system) {
            chaosExperiment(
              "phase children",
              stall(),
              5.seconds,
              baseline = {
                CoroutineScope(currentCoroutineContext()).launch(start = CoroutineStart.UNDISPATCHED) {
                  baselineStarted.complete(Unit)
                  releaseBaseline.await()
                }
              },
              faulted = {
                CoroutineScope(currentCoroutineContext()).launch(start = CoroutineStart.UNDISPATCHED) {
                  faultStarted.complete(Unit)
                  releaseFault.await()
                  fixture.controller.active shouldBe true
                }
              },
              recovery = {
                fixture.controller.active shouldBe false
                CoroutineScope(currentCoroutineContext()).launch(start = CoroutineStart.UNDISPATCHED) {
                  recoveryStarted.complete(Unit)
                  releaseRecovery.await()
                }
              }
            )
            finished.complete(Unit)
          }
        }
        baselineStarted.await()
        faultStarted.isCompleted shouldBe false
        fixture.controller.active shouldBe false
        releaseBaseline.complete(Unit)
        faultStarted.await()
        recoveryStarted.isCompleted shouldBe false
        fixture.controller.active shouldBe true
        releaseFault.complete(Unit)
        recoveryStarted.await()
        finished.isCompleted shouldBe false
        shouldThrow<IllegalStateException> {
          withSystemValidation(fixture.system) { duringFault {} }
        }
        releaseRecovery.complete(Unit)
        experiment.join()
        finished.isCompleted shouldBe true
      }
      fixture.controller.heals shouldBe 1
    }
  }

  test("coordinator captures an observation before waiting outside its ownership lock") {
    ChaosFixture().use { fixture ->
      fixture.start()
      coroutineScope {
        val scope = ChaosValidationScope(fixture.system, currentCoroutineContext().job)
        val captured = CompletableDeferred<Unit>()
        val proceed = CompletableDeferred<Unit>()
        fixture.controller.beforeObservationAwait = {
          captured.complete(Unit)
          proceed.await()
        }
        lateinit var waiting: Deferred<IllegalStateException>
        try {
          fixture.coordinator.duringFault(scope, stall()) {
            // Deliberately external to the fault phase to exercise rollover before handle.await resumes.
            waiting = async(start = CoroutineStart.UNDISPATCHED) {
              shouldThrow<IllegalStateException> {
                fixture.coordinator.awaitStalledTraffic(scope, TrafficDirection.Requests, 2.seconds)
              }
            }
            captured.await()
          }
          fixture.coordinator.duringFault(scope, stall()) {
            proceed.complete(Unit)
            waiting.await().message shouldBe "Fault ended before stalled traffic was observed"
          }
        } finally {
          fixture.coordinator.release(scope)
        }
      }
    }
  }

  test("cancellation in recovery releases experiment ownership after healing") {
    ChaosFixture().use { fixture ->
      fixture.start()
      coroutineScope {
        val recovering = CompletableDeferred<Unit>()
        val experiment = launch {
          withSystemValidation(fixture.system) {
            chaosExperiment("cancel recovery", stall(), 5.seconds, {}, {}, {
              recovering.complete(Unit)
              awaitCancellation()
            })
          }
        }
        recovering.await()
        fixture.controller.active shouldBe false
        experiment.cancelAndJoin()
      }
      fixture.controller.heals shouldBe 1
      withSystemValidation(fixture.system) { duringFault {} }
    }
  }

  test("experiment timeout heals and releases ownership without replaying actions") {
    ChaosFixture().use { fixture ->
      fixture.start()
      var actions = 0
      shouldThrow<TimeoutCancellationException> {
        withSystemValidation(fixture.system) {
          chaosExperiment(
            "bounded outage",
            stall(),
            50.milliseconds,
            baseline = {},
            faulted = {
              actions++
              awaitCancellation()
            },
            recovery = { error("timed out experiments do not report successful recovery") }
          )
        }
      }
      actions shouldBe 1
      fixture.controller.active shouldBe false
      fixture.controller.heals shouldBe 1
      withSystemValidation(fixture.system) { duringFault {} }
    }
  }

  test("baseline failure never applies a fault or runs recovery") {
    ChaosFixture().use { fixture ->
      fixture.start()
      shouldThrow<IllegalArgumentException> {
        withSystemValidation(fixture.system) {
          chaosExperiment(
            "unhealthy baseline",
            stall(),
            2.seconds,
            baseline = { throw IllegalArgumentException("unhealthy") },
            faulted = { error("must not run") },
            recovery = { error("must not run") }
          )
        }
      }
      fixture.controller.active shouldBe false
      fixture.controller.heals shouldBe 0
      withSystemValidation(fixture.system) { duringFault {} }
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
      val actual = shouldThrow<IllegalArgumentException> {
        withSystemValidation(fixture.system) { duringFault { throw primary } }
      }
      actual.message shouldBe primary.message
      generateSequence(actual as Throwable) { it.cause }.any { it === primary } shouldBe true
      primary.suppressed.toList() shouldBe listOf(cleanup)
      shouldThrow<IllegalStateException> {
        withSystemValidation(fixture.system) { duringFault {} }
      }.message shouldBe "Chaos route ChaosTestSystem is unavailable; recreate the fixture"
      fixture.controller.healFailure = null
    }
  }

  test("a phase child failure preserves failed restoration on its original exception") {
    ChaosFixture().use { fixture ->
      fixture.start()
      val primary = IllegalArgumentException("child assertion failed")
      val cleanup = IllegalStateException("cannot heal child fault")
      fixture.controller.healFailure = cleanup
      val actual = shouldThrow<IllegalArgumentException> {
        withSystemValidation(fixture.system) {
          chaosExperiment("child failure", stall(), 2.seconds, {}, {
            CoroutineScope(currentCoroutineContext()).launch { throw primary }
          }, { error("failed experiments do not recover") })
        }
      }
      actual.message shouldBe primary.message
      generateSequence(actual as Throwable) { it.cause }.any { it === primary } shouldBe true
      primary.suppressed.toList() shouldBe listOf(cleanup)
      fixture.controller.heals shouldBe 1
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

  test("shutdown heals before waiting for an owner blocked in synchronous I/O") {
    ChaosFixture().use { fixture ->
      fixture.start()
      val unblocked = CountDownLatch(1)
      fixture.controller.onHeal = { unblocked.countDown() }
      coroutineScope {
        val entered = CompletableDeferred<Unit>()
        val owner = launch(Dispatchers.IO) {
          withSystemValidation(fixture.system) {
            duringFault {
              entered.complete(Unit)
              // Model a bounded client read that coroutine cancellation cannot release.
              check(unblocked.await(2, TimeUnit.SECONDS)) { "Shutdown waited for I/O before healing" }
            }
          }
        }
        entered.await()
        fixture.coordinator.beforeApplicationStop()
        owner.isCompleted shouldBe true
      }
      fixture.controller.heals shouldBe 1
      fixture.controller.active shouldBe false
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
  private val traffic = TcpTrafficGate()
  var beforeObservationAwait: suspend () -> Unit = {}
  var active = false
  var heals = 0
  var applyFailure: Throwable? = null
  var healFailure: Throwable? = null
  var onHeal: () -> Unit = {}

  override fun start(target: TcpEndpoint): TcpEndpoint = endpoint
  override fun apply(fault: NetworkFault) {
    active = true
    traffic.changeFault(fault)
    applyFailure?.let { throw it }
  }
  override fun heal() {
    heals++
    healFailure?.let { throw it }
    active = false
    traffic.changeFault(null)
    onHeal()
  }
  override fun captureStalledTraffic(direction: TrafficDirection): StalledTrafficObservation {
    val observation = traffic.captureStalledTraffic(direction)
    return StalledTrafficObservation { timeout ->
      beforeObservationAwait()
      observation.await(timeout)
    }
  }
  override fun close() = onClose(endpoint)
}
