package com.trendyol.stove.examples.kotlin.spring.infra

import com.trendyol.stove.examples.kotlin.spring.infra.clients.InventoryNotAvailableException
import com.trendyol.stove.examples.kotlin.spring.infra.clients.PaymentFailedException
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

private val logger = KotlinLogging.logger {}

@RestControllerAdvice
class GlobalErrorHandler {
  @ExceptionHandler(InventoryNotAvailableException::class)
  fun handleInventoryNotAvailable(ex: InventoryNotAvailableException): ResponseEntity<ErrorResponse> {
    logger.warn(ex) { "Inventory not available" }
    return ResponseEntity
      .status(HttpStatus.CONFLICT)
      .body(ErrorResponse(message = ex.message ?: "Inventory not available", errorCode = "INVENTORY_NOT_AVAILABLE"))
  }

  @ExceptionHandler(PaymentFailedException::class)
  fun handlePaymentFailed(ex: PaymentFailedException): ResponseEntity<ErrorResponse> {
    logger.error(ex) { "Payment failed" }
    return ResponseEntity
      .status(HttpStatus.BAD_GATEWAY)
      .body(ErrorResponse(message = ex.message ?: "Payment failed", errorCode = "PAYMENT_FAILED"))
  }

  @ExceptionHandler(Exception::class)
  fun handleGenericException(ex: Exception): ResponseEntity<ErrorResponse> {
    logger.error(ex) { "Unexpected error occurred" }
    return ResponseEntity
      .status(HttpStatus.INTERNAL_SERVER_ERROR)
      .body(ErrorResponse(message = "Internal server error", errorCode = "INTERNAL_ERROR"))
  }
}

data class ErrorResponse(
  val message: String,
  val errorCode: String
)
