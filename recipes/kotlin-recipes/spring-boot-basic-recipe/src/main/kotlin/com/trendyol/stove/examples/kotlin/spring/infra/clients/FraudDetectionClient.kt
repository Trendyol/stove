package com.trendyol.stove.examples.kotlin.spring.infra.clients

import com.trendyol.stove.examples.kotlin.spring.grpc.CheckFraudRequest
import com.trendyol.stove.examples.kotlin.spring.grpc.CheckFraudResponse
import com.trendyol.stove.examples.kotlin.spring.grpc.FraudDetectionServiceGrpcKt
import io.github.oshai.kotlinlogging.KotlinLogging
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import io.opentelemetry.instrumentation.annotations.WithSpan
import jakarta.annotation.PreDestroy
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

private val logger = KotlinLogging.logger {}

@Component
class FraudDetectionClient(
  @param:Value("\${external-apis.fraud-detection.host}") private val host: String,
  @param:Value("\${external-apis.fraud-detection.port}") private val port: Int
) {
  private val channel: ManagedChannel = ManagedChannelBuilder
    .forAddress(host, port)
    .usePlaintext()
    .build()

  private val stub = FraudDetectionServiceGrpcKt.FraudDetectionServiceCoroutineStub(channel)

  @WithSpan("FraudDetectionClient.checkFraud")
  suspend fun checkFraud(
    orderId: String,
    userId: String,
    amount: Double,
    productId: String
  ): FraudCheckResult {
    logger.info { "Checking fraud for order=$orderId, user=$userId, amount=$amount" }

    val request = CheckFraudRequest.newBuilder()
      .setOrderId(orderId)
      .setUserId(userId)
      .setAmount(amount)
      .setProductId(productId)
      .build()

    val response: CheckFraudResponse = stub.checkFraud(request)

    return FraudCheckResult(
      isFraudulent = response.isFraudulent,
      riskScore = response.riskScore,
      reason = response.reason
    )
  }

  @PreDestroy
  fun shutdown() {
    channel.shutdown()
  }
}

data class FraudCheckResult(
  val isFraudulent: Boolean,
  val riskScore: Double,
  val reason: String
)

class FraudDetectedException(reason: String) :
  RuntimeException("Order flagged as fraudulent: $reason")
