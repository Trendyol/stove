@file:Suppress("UNCHECKED_CAST")

package com.trendyol.stove.examples.kotlin.spring.e2e.setup

import arrow.core.*
import com.github.kagkarlsson.scheduler.event.AbstractSchedulerListener
import com.github.kagkarlsson.scheduler.task.*
import com.trendyol.stove.reporting.*
import com.trendyol.stove.system.*
import com.trendyol.stove.system.abstractions.*
import com.trendyol.stove.system.annotations.StoveDsl
import kotlinx.coroutines.*
import org.springframework.beans.factory.getBean
import org.springframework.context.ApplicationContext
import java.time.Instant
import java.util.concurrent.*
import kotlin.reflect.KClass
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Listener that tracks db-scheduler task executions for testing purposes.
 * Captures scheduled, completed, and failed task executions.
 */
class StoveDbSchedulerListener : AbstractSchedulerListener() {
  private val completedExecutions: ConcurrentMap<String, ExecutionComplete> = ConcurrentHashMap()
  private val failedExecutions: ConcurrentMap<String, ExecutionComplete> = ConcurrentHashMap()
  private val scheduledExecutions: ConcurrentMap<String, Instant> = ConcurrentHashMap()

  override fun onExecutionComplete(executionComplete: ExecutionComplete) {
    val instanceId = executionComplete.execution.taskInstance.id
    completedExecutions[instanceId] = executionComplete

    // Track failures separately for easy access
    if (executionComplete.result == ExecutionComplete.Result.FAILED) {
      failedExecutions[instanceId] = executionComplete
    }
  }

  override fun onExecutionScheduled(taskInstanceId: TaskInstanceId, executionTime: Instant) {
    scheduledExecutions[taskInstanceId.id] = executionTime
  }

  /**
   * Returns a snapshot of completed executions for reporting.
   */
  fun getCompletedExecutionsSnapshot(): List<Map<String, Any?>> =
    completedExecutions.map { (id, execution) ->
      mapOf(
        "instanceId" to id,
        "taskName" to execution.execution.taskInstance.taskName,
        "result" to execution.result.toString(),
        "payloadType" to execution.execution.taskInstance.data
          ?.javaClass
          ?.simpleName
      )
    }

  /**
   * Returns a snapshot of failed executions for reporting.
   */
  fun getFailedExecutionsSnapshot(): List<Map<String, Any?>> =
    failedExecutions.map { (id, execution) ->
      mapOf(
        "instanceId" to id,
        "taskName" to execution.execution.taskInstance.taskName,
        "result" to execution.result.toString(),
        "cause" to execution.cause.orElse(null)?.message,
        "payloadType" to execution.execution.taskInstance.data
          ?.javaClass
          ?.simpleName
      )
    }

  /**
   * Returns a snapshot of scheduled executions for reporting.
   */
  fun getScheduledExecutionsSnapshot(): List<Map<String, Any?>> =
    scheduledExecutions.map { (id, time) ->
      mapOf("instanceId" to id, "executionTime" to time.toString())
    }

  /**
   * Waits until a task execution with the specified payload type and condition is observed.
   * Throws assertion error if the task execution failed.
   */
  suspend fun <T : Any> waitUntilObservedSuccessfully(
    atLeastIn: Duration,
    clazz: KClass<T>,
    condition: (T) -> Boolean
  ): Collection<ExecutionComplete> = coroutineScope {
    val matchingExecutions = waitForMatchingExecutions(atLeastIn, clazz, condition)

    // Check if any matching execution failed
    val failedMatches = matchingExecutions.filter { it.result == ExecutionComplete.Result.FAILED }
    if (failedMatches.isNotEmpty()) {
      val failures = failedMatches.map { exec ->
        val cause = exec.cause.orElse(null)
        "Task '${exec.execution.taskInstance.taskName}' " +
          "(instance: ${exec.execution.taskInstance.id}) " +
          "FAILED: ${cause?.message ?: "Unknown error"}"
      }
      throw AssertionError(
        "Task execution(s) failed:\n${failures.joinToString("\n")}\n" +
          "Expected: successful execution of ${clazz.simpleName}"
      )
    }

    matchingExecutions
  }

  private suspend fun <T : Any> waitForMatchingExecutions(
    atLeastIn: Duration,
    clazz: KClass<T>,
    condition: (T) -> Boolean
  ): Collection<ExecutionComplete> {
    val getExecutions = { completedExecutions.values.toList() }

    return getExecutions.waitUntilConditionMet(
      atLeastIn,
      "While OBSERVING ${clazz.java.simpleName}"
    ) { execution ->
      val data = execution.execution.taskInstance?.data ?: return@waitUntilConditionMet false
      when {
        clazz.java.isAssignableFrom(data.javaClass) -> condition(data as T)
        else -> false
      }
    }
  }

  private suspend fun <T> (() -> Collection<T>).waitUntilConditionMet(
    duration: Duration,
    subject: String,
    condition: (T) -> Boolean
  ): Collection<T> = runCatching {
    val collectionFunc = this
    withTimeout(duration) { while (!collectionFunc().any { condition(it) }) delay(50) }
    return collectionFunc().filter { condition(it) }
  }.recoverCatching {
    when (it) {
      is TimeoutCancellationException -> throw AssertionError("GOT A TIMEOUT: $subject.")
      is ConcurrentModificationException -> Result.success(waitUntilConditionMet(duration, subject, condition))
      else -> throw it
    }.getOrThrow()
  }.getOrThrow()
}

/**
 * Stove system for testing db-scheduler task executions.
 * Allows assertions on scheduled tasks being executed with expected payloads.
 */
class DbSchedulerSystem(
  override val stove: Stove
) : PluggedSystem,
  AfterRunAwareWithContext<ApplicationContext>,
  Reports {
  lateinit var listener: StoveDbSchedulerListener

  override val reportSystemName: String = "DbScheduler"

  override suspend fun afterRun(context: ApplicationContext) {
    listener = context.getBean()
  }

  override fun snapshot(): SystemSnapshot = SystemSnapshot(
    system = reportSystemName,
    state = mapOf(
      "completedExecutions" to listener.getCompletedExecutionsSnapshot(),
      "failedExecutions" to listener.getFailedExecutionsSnapshot(),
      "scheduledExecutions" to listener.getScheduledExecutionsSnapshot()
    ),
    summary = buildString {
      val completed = listener.getCompletedExecutionsSnapshot()
      val failed = listener.getFailedExecutionsSnapshot()
      val scheduled = listener.getScheduledExecutionsSnapshot()
      append("Completed: ${completed.size} task(s)")
      if (completed.isNotEmpty()) {
        append(" [${completed.joinToString { it["taskName"].toString() }}]")
      }
      if (failed.isNotEmpty()) {
        append(", FAILED: ${failed.size} task(s)")
        append(" [${failed.joinToString { "${it["taskName"]}: ${it["cause"]}" }}]")
      }
      append(", Scheduled: ${scheduled.size} task(s)")
    }
  )

  /**
   * Asserts that a task with the specified payload type was executed successfully.
   * Fails if the task execution itself failed (e.g., threw an exception).
   *
   * @param atLeastIn Maximum time to wait for the task execution
   * @param condition Predicate to match the task payload
   */
  suspend inline fun <reified T : Any> shouldBeExecuted(
    atLeastIn: Duration = 5.seconds,
    noinline condition: T.() -> Boolean
  ): DbSchedulerSystem = report(
    action = "Assert task executed successfully: ${T::class.simpleName}",
    expected = "Task with ${T::class.simpleName} payload executed successfully".some(),
    metadata = mapOf("timeout" to atLeastIn.toString())
  ) { listener.waitUntilObservedSuccessfully(atLeastIn, T::class, condition) }.let { this }

  override fun close() = Unit
}

// ============================================================================
// DSL Extensions
// ============================================================================

/**
 * Registers the DbSchedulerSystem with Stove.
 */
@StoveDsl
fun Stove.withDbSchedulerListener(): Stove = getOrRegister(DbSchedulerSystem(this)).let { this }

/**
 * Gets the registered DbSchedulerSystem.
 */
@StoveDsl
fun Stove.dbScheduler(): DbSchedulerSystem =
  getOrNone<DbSchedulerSystem>().getOrElse { throw SystemNotRegisteredException(DbSchedulerSystem::class) }

/**
 * DSL extension for registering DbSchedulerSystem during Stove setup.
 */
@StoveDsl
fun WithDsl.dbScheduler(): Stove = this.stove.withDbSchedulerListener()

/**
 * DSL extension for asserting on scheduled tasks during validation.
 */
@StoveDsl
suspend fun ValidationDsl.tasks(validation: suspend DbSchedulerSystem.() -> Unit): Unit =
  validation(this.stove.dbScheduler())
