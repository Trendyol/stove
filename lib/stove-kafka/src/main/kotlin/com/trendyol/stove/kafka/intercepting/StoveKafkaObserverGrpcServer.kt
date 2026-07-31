package com.trendyol.stove.kafka.intercepting

import com.trendyol.stove.kafka.*
import org.slf4j.*

class StoveKafkaObserverGrpcServer(
  private val recorder: KafkaRecorder
) : StoveKafkaObserverServiceWireGrpc.StoveKafkaObserverServiceImplBase() {
  private val logger: Logger = LoggerFactory.getLogger(javaClass)

  override suspend fun healthCheck(request: HealthCheckRequest): HealthCheckResponse {
    logger.debug("Received Kafka observer health check: {}", request)
    return HealthCheckResponse(status = HealthCheckResponse.ServingStatus.SERVING)
  }

  override suspend fun onPublishedMessage(request: PublishedMessage): Reply {
    logger.debug("Received published Kafka message: {}", request)
    recorder.onMessagePublished(request)
    return Reply(status = 200)
  }

  override suspend fun onConsumedMessage(request: ConsumedMessage): Reply {
    logger.debug("Received consumed Kafka message: {}", request)
    recorder.onMessageConsumed(request)
    return Reply(status = 200)
  }

  override suspend fun onCommittedMessage(request: CommittedMessage): Reply {
    logger.debug("Received committed Kafka message: {}", request)
    recorder.onMessageCommitted(request)
    return Reply(status = 200)
  }

  override suspend fun onAcknowledgedMessage(request: AcknowledgedMessage): Reply {
    logger.debug("Received acknowledged Kafka message: {}", request)
    recorder.onMessageAcknowledged(request)
    return Reply(status = 200)
  }
}
