package com.trendyol.stove.examples.kotlin.spring.domain.order

import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/orders")
class OrderController(
  private val orderService: OrderService
) {
  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  suspend fun createOrder(
    @RequestBody request: CreateOrderRequest
  ): OrderResponse {
    val order = orderService.createOrder(
      userId = request.userId,
      productId = request.productId,
      amount = request.amount
    )
    return OrderResponse(
      orderId = order.id,
      userId = order.userId,
      productId = order.productId,
      amount = order.amount,
      status = order.status.name
    )
  }

  @GetMapping("/{id}")
  suspend fun getOrder(
    @PathVariable id: String
  ): OrderResponse? {
    val order = orderService.getOrder(id) ?: return null
    return OrderResponse(
      orderId = order.id,
      userId = order.userId,
      productId = order.productId,
      amount = order.amount,
      status = order.status.name
    )
  }
}

data class CreateOrderRequest(
  val userId: String,
  val productId: String,
  val amount: Double
)

data class OrderResponse(
  val orderId: String,
  val userId: String,
  val productId: String,
  val amount: Double,
  val status: String
)
