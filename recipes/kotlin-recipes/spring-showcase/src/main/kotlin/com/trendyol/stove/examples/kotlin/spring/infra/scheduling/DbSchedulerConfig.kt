package com.trendyol.stove.examples.kotlin.spring.infra.scheduling

import com.github.kagkarlsson.scheduler.boot.config.DbSchedulerCustomizer
import com.github.kagkarlsson.scheduler.event.AbstractSchedulerListener
import com.github.kagkarlsson.scheduler.task.ExecutionComplete
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component

private val logger = KotlinLogging.logger {}

/**
 * Customizer for db-scheduler configuration.
 */
@Component
class DbSchedulerCustomizerConfig : DbSchedulerCustomizer

/**
 * Listener for db-scheduler task executions.
 * Logs task execution results for observability.
 */
@Component
class DbSchedulerLoggingListener : AbstractSchedulerListener() {
  override fun onExecutionComplete(executionComplete: ExecutionComplete) {
    logger.info {
      "Task execution completed: " +
        "task=${executionComplete.execution.taskInstance.taskName}, " +
        "instanceId=${executionComplete.execution.taskInstance.id}, " +
        "result=${executionComplete.result}"
    }
  }
}
