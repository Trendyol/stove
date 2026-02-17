package com.trendyol.stove.examples.kotlin.spring.infra

import com.trendyol.stove.examples.kotlin.spring.infra.clients.*
import io.github.oshai.kotlinlogging.KotlinLogging
import io.opentelemetry.api.trace.*
import org.springframework.http.*
import org.springframework.web.bind.annotation.*

private val logger = KotlinLogging.logger {}

@RestControllerAdvice
class GlobalErrorHandler {
  @ExceptionHandler(InventoryNotAvailableException::class)
  fun handleInventoryNotAvailable(ex: InventoryNotAvailableException): ResponseEntity<ErrorResponse> {
    logger.warn(ex) { "Inventory not available" }
    Span.current().apply {
      recordException(ex)
      setStatus(StatusCode.ERROR, ex.message ?: "Unknown error")
    }
    return ResponseEntity
      .status(HttpStatus.CONFLICT)
      .body(ErrorResponse(message = ex.message ?: "Inventory not available", errorCode = "INVENTORY_NOT_AVAILABLE"))
  }

  @ExceptionHandler(PaymentFailedException::class)
  fun handlePaymentFailed(ex: PaymentFailedException): ResponseEntity<ErrorResponse> {
    logger.error(ex) { "Payment failed" }
    Span.current().apply {
      recordException(ex)
      setStatus(StatusCode.ERROR, ex.message ?: "Unknown error")
    }
    return ResponseEntity
      .status(HttpStatus.BAD_GATEWAY)
      .body(ErrorResponse(message = ex.message ?: "Payment failed", errorCode = "PAYMENT_FAILED"))
  }

  @ExceptionHandler(Exception::class)
  fun handleGenericException(ex: Exception): ResponseEntity<ErrorResponse> {
    logger.error(ex) { "Unexpected error occurred" }
    Span.current().apply {
      recordException(ex)
      setStatus(StatusCode.ERROR, ex.message ?: "Unknown error")
    }
    return ResponseEntity
      .status(HttpStatus.INTERNAL_SERVER_ERROR)
      .body(ErrorResponse(message = "Internal server error", errorCode = "INTERNAL_ERROR"))
  }
}

data class ErrorResponse(
  val message: String,
  val errorCode: String
)
