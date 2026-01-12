package stove.ktor.example.infrastructure

import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import stove.ktor.example.grpc.*
import java.util.concurrent.TimeUnit

/**
 * gRPC client for the external Feature Toggle service.
 *
 * This client calls a hypothetical external Feature Toggle microservice to:
 * - Check if specific features are enabled
 * - Get all feature flags for a context
 */
class FeatureToggleClient(
  private val host: String,
  private val port: Int
) : AutoCloseable {
  private val channel: ManagedChannel = ManagedChannelBuilder
    .forAddress(host, port)
    .usePlaintext()
    .build()

  private val stub: FeatureToggleServiceGrpcKt.FeatureToggleServiceCoroutineStub =
    FeatureToggleServiceGrpcKt.FeatureToggleServiceCoroutineStub(channel)

  /**
   * Check if a feature is enabled for a given context.
   */
  suspend fun isFeatureEnabled(featureName: String, context: String): IsFeatureEnabledResponse {
    val request = isFeatureEnabledRequest {
      this.featureName = featureName
      this.context = context
    }
    return stub.isFeatureEnabled(request)
  }

  /**
   * Get all feature flags for a context.
   */
  suspend fun getFeatures(context: String): GetFeaturesResponse {
    val request = getFeaturesRequest {
      this.context = context
    }
    return stub.getFeatures(request)
  }

  @Suppress("MagicNumber")
  override fun close() {
    channel.shutdown().awaitTermination(5, TimeUnit.SECONDS)
  }
}
