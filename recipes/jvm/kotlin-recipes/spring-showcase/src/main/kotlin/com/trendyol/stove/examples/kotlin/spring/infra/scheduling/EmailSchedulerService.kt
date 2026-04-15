package com.trendyol.stove.examples.kotlin.spring.infra.scheduling

import com.github.kagkarlsson.scheduler.Scheduler
import com.github.kagkarlsson.scheduler.task.Task
import io.github.oshai.kotlinlogging.KotlinLogging
import io.opentelemetry.instrumentation.annotations.SpanAttribute
import io.opentelemetry.instrumentation.annotations.WithSpan
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID

private val logger = KotlinLogging.logger {}

/**
 * Service for scheduling order-related email tasks.
 * Uses db-scheduler for persistent, reliable task scheduling.
 *
 * This demonstrates:
 * - Integration with db-scheduler for persistent scheduling
 * - OpenTelemetry instrumentation for observability
 * - Stove testing with DbSchedulerSystem
 */
@Service
class EmailSchedulerService(
  private val scheduler: Scheduler,
  private val sendOrderEmailTask: Task<OrderEmailPayload>
) {
  /**
   * Schedules an order confirmation email to be sent.
   *
   * @param orderId The order ID
   * @param userId The user ID
   * @param email The email address (defaults to userId@example.com)
   * @param amount The order amount
   * @param productId The product ID
   * @param executeAt When to send the email (defaults to now)
   */
  @WithSpan("EmailSchedulerService.scheduleOrderConfirmationEmail")
  fun scheduleOrderConfirmationEmail(
    @SpanAttribute("orderId") orderId: String,
    @SpanAttribute("userId") userId: String,
    email: String = "$userId@example.com",
    amount: Double,
    productId: String,
    executeAt: Instant = Instant.now()
  ) {
    val payload = OrderEmailPayload(
      orderId = orderId,
      userId = userId,
      email = email,
      amount = amount,
      productId = productId
    )

    val taskInstanceId = "order-email-$orderId-${UUID.randomUUID()}"

    logger.info {
      "Scheduling order confirmation email for order $orderId to $email at $executeAt"
    }

    scheduler.scheduleIfNotExists(
      sendOrderEmailTask.instance(taskInstanceId, payload),
      executeAt
    )

    logger.info { "Successfully scheduled email task: $taskInstanceId" }
  }
}
