package com.trendyol.stove.examples.kotlin.spring.e2e.tests

import arrow.core.some
import com.trendyol.stove.examples.kotlin.spring.domain.order.*
import com.trendyol.stove.examples.kotlin.spring.e2e.setup.tasks
import com.trendyol.stove.examples.kotlin.spring.events.*
import com.trendyol.stove.examples.kotlin.spring.grpc.*
import com.trendyol.stove.examples.kotlin.spring.infra.clients.*
import com.trendyol.stove.examples.kotlin.spring.infra.scheduling.OrderEmailPayload
import com.trendyol.stove.grpc.grpc
import com.trendyol.stove.http.http
import com.trendyol.stove.kafka.kafka
import com.trendyol.stove.postgres.postgresql
import com.trendyol.stove.system.*
import com.trendyol.stove.testing.grpcmock.grpcMock
import com.trendyol.stove.wiremock.wiremock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.*
import java.util.*
import kotlin.time.Duration.Companion.seconds

/**
 * THE SHOWCASE - One comprehensive test demonstrating all Stove features.
 *
 * Walk through this test section-by-section during your presentation.
 *
 * ══════════════════════════════════════════════════════════════════════════════
 * 🎯 TWO WAYS TO DEMO FAILURE REPORTS:
 * ══════════════════════════════════════════════════════════════════════════════
 *
 * Option 1: ASSERTION FAILURE (simple)
 *   → Edit line ~110: change "CONFIRMED" to "WRONG_STATUS"
 *   → Shows: Test assertion failed, with full trace of what happened
 *
 * Option 2: DEEP APPLICATION BUG (realistic) ⭐ RECOMMENDED
 *   → Go to PostgresOrderRepository.kt
 *   → Uncomment the line: // validateOrderAmount(order)
 *   → Shows: Bug deep in persistence layer, test assertions are correct!
 *   → The trace reveals the exact failure point in the call stack
 *
 * The amount ($2499.99) is intentionally > $1000 to trigger the demo bug.
 * ══════════════════════════════════════════════════════════════════════════════
 */
class TheShowcase :
  FunSpec({

    test("The Complete Order Flow - Every Feature in One Test") {
      stove {
        val userId = "user-${UUID.randomUUID()}"
        val productId = "macbook-pro-16"
        val amount = 2499.99
        var orderId: String? = null

        // ══════════════════════════════════════════════════════════════
        // SECTION 1: gRPC Mock - Mock External gRPC Service
        // "First, we mock the Fraud Detection gRPC service"
        // ══════════════════════════════════════════════════════════════

        grpcMock {
          mockUnary(
            serviceName = "frauddetection.FraudDetectionService",
            methodName = "CheckFraud",
            response = CheckFraudResponse
              .newBuilder()
              .setIsFraudulent(false)
              .setRiskScore(0.15)
              .setReason("low_risk_user")
              .build()
          )
        }

        // ══════════════════════════════════════════════════════════════
        // SECTION 2: WireMock - Mock External REST APIs
        // "Our order service also calls inventory and payment APIs"
        // ══════════════════════════════════════════════════════════════

        wiremock {
          mockGet(
            url = "/inventory/$productId",
            statusCode = 200,
            responseBody = InventoryResponse(
              productId = productId,
              available = true,
              quantity = 10
            ).some()
          )

          mockPost(
            url = "/payments/charge",
            statusCode = 200,
            responseBody = PaymentResult(
              success = true,
              transactionId = "txn-${UUID.randomUUID()}",
              amount = amount
            ).some()
          )
        }

        // ══════════════════════════════════════════════════════════════
        // SECTION 3: HTTP - Call Our API
        // "Now we call our order endpoint"
        // ══════════════════════════════════════════════════════════════

        http {
          postAndExpectBody<OrderResponse>(
            uri = "/api/orders",
            body = CreateOrderRequest(
              userId = userId,
              productId = productId,
              amount = amount
            ).some()
          ) { response ->
            response.status shouldBe 201
            response.body().status shouldBe "CONFIRMED"
            response.body().orderId shouldNotBe null
            orderId = response.body().orderId
          }
        }

        // ══════════════════════════════════════════════════════════════
        // SECTION 4: Database - Verify State
        // "Let's check what's actually in the database"
        // ══════════════════════════════════════════════════════════════

        postgresql {
          shouldQuery<OrderRow>(
            query = "SELECT id, user_id, product_id, amount, status FROM orders WHERE user_id = '$userId'",
            mapper = { row ->
              OrderRow(
                id = row.string("id"),
                userId = row.string("user_id"),
                productId = row.string("product_id"),
                amount = row.double("amount"),
                status = row.string("status")
              )
            }
          ) { orders ->
            orders.size shouldBe 1
            orders.first().apply {
              this.userId shouldBe userId
              this.productId shouldBe productId
              this.amount shouldBe amount
              this.status shouldBe "CONFIRMED" // <-- EDIT THIS TO "WRONG_STATUS" TO SHOW FAILURE REPORT
            }
          }
        }

        // ══════════════════════════════════════════════════════════════
        // SECTION 5: Kafka - Verify Events Published
        // "And check that the right events were published"
        // ══════════════════════════════════════════════════════════════

        kafka {
          shouldBePublished<OrderCreatedEvent>(10.seconds) {
            actual.userId == userId && actual.productId == productId
          }

          shouldBePublished<PaymentProcessedEvent>(10.seconds) {
            actual.amount == amount && actual.success
          }
        }

        // ══════════════════════════════════════════════════════════════
        // SECTION 5b: Kafka - Verify Events Consumed + Side Effects
        // "Now let's verify the consumer processed the event AND
        //  updated the read model (CQRS pattern)"
        // ══════════════════════════════════════════════════════════════

        kafka {
          shouldBeConsumed<OrderCreatedEvent>(10.seconds) {
            actual.userId == userId && actual.orderId == orderId
          }
        }

        // Verify the side effect: statistics read model was updated
        postgresql {
          shouldQuery<UserStatisticsRow>(
            query = "SELECT user_id, total_orders, total_amount FROM user_order_statistics WHERE user_id = '$userId'",
            mapper = { row ->
              UserStatisticsRow(
                userId = row.string("user_id"),
                totalOrders = row.int("total_orders"),
                totalAmount = row.double("total_amount")
              )
            }
          ) { stats ->
            stats.size shouldBe 1
            stats.first().apply {
              this.userId shouldBe userId
              this.totalOrders shouldBe 1
              this.totalAmount shouldBe amount
            }
          }
        }

        // ══════════════════════════════════════════════════════════════
        // SECTION 6: gRPC - Test OUR gRPC Server
        // "Our app also exposes a gRPC API for querying orders"
        // ══════════════════════════════════════════════════════════════

        grpc {
          channel<OrderQueryServiceGrpcKt.OrderQueryServiceCoroutineStub> {
            // Query order by ID via gRPC
            val orderById = getOrder(
              GetOrderRequest
                .newBuilder()
                .setOrderId(orderId!!)
                .build()
            )
            orderById.found shouldBe true
            orderById.order.userId shouldBe userId
            orderById.order.status shouldBe "CONFIRMED"

            // Query orders by user via gRPC
            val ordersByUser = getOrdersByUser(
              GetOrdersByUserRequest
                .newBuilder()
                .setUserId(userId)
                .build()
            )
            ordersByUser.ordersCount shouldBe 1
            ordersByUser.ordersList.first().productId shouldBe productId
          }
        }

        // ══════════════════════════════════════════════════════════════
        // SECTION 7: Bridge - Access Application Beans
        // "We can also access our services directly"
        // ══════════════════════════════════════════════════════════════

        using<OrderService> {
          val order = getOrderByUserId(userId)
          order shouldNotBe null
          order!!.status shouldBe OrderStatus.CONFIRMED
        }

        // ══════════════════════════════════════════════════════════════
        // SECTION 8: db-scheduler - Verify Scheduled Tasks
        // "When an order is created, we schedule a confirmation email.
        //  Let's verify the task was executed with the correct payload.
        //  This showcases how to write your own Stove System!"
        // ══════════════════════════════════════════════════════════════

        tasks {
          shouldBeExecuted<OrderEmailPayload> {
            this.orderId == orderId &&
              this.userId == userId &&
              this.amount == amount
          }
        }
      }
    }
  })

/**
 * Simple data class for mapping database rows.
 */
data class OrderRow(
  val id: String,
  val userId: String,
  val productId: String,
  val amount: Double,
  val status: String
)

/**
 * Data class for mapping user statistics rows.
 */
data class UserStatisticsRow(
  val userId: String,
  val totalOrders: Int,
  val totalAmount: Double
)
