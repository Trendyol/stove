package com.trendyol.stove.examples.kotlin.spring.infra.scheduling

import com.github.kagkarlsson.scheduler.task.Task
import com.github.kagkarlsson.scheduler.task.helper.Tasks
import io.github.oshai.kotlinlogging.KotlinLogging
import io.opentelemetry.instrumentation.annotations.WithSpan
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.io.Serializable

/**
 * Payload for the order email task.
 * Contains all information needed to send an order confirmation email.
 */
data class OrderEmailPayload(
  val orderId: String,
  val userId: String,
  val email: String,
  val amount: Double,
  val productId: String
) : Serializable {
  companion object {
    private const val serialVersionUID: Long = 1L
  }
}

private val logger = KotlinLogging.logger {}

@Configuration
class SendOrderEmailTaskConfig {
  @Bean
  fun sendOrderEmailTask(): Task<OrderEmailPayload> =
    Tasks
      .oneTime("send-order-email", OrderEmailPayload::class.java)
      .execute { taskInstance, _ ->
        val payload = taskInstance.data
        sendEmail(payload)
      }

  @WithSpan("SendOrderEmailTask.sendEmail")
  private fun sendEmail(payload: OrderEmailPayload) {
    // Simulate sending email - in production this would call an email service
    logger.info {
      """
      |============================================
      | SENDING ORDER CONFIRMATION EMAIL
      |============================================
      | To: ${payload.email}
      | Order ID: ${payload.orderId}
      | User ID: ${payload.userId}
      | Product: ${payload.productId}
      | Amount: $${payload.amount}
      |============================================
      """.trimMargin()
    }

    // Simulate email sending delay
    Thread.sleep(100)

    logger.info { "Email sent successfully for order ${payload.orderId}" }
  }
}
