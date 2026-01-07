package com.trendyol.stove.kafka.intercepting

import com.trendyol.stove.kafka.*
import org.slf4j.*

class StoveKafkaObserverGrpcServer(
  private val sink: StoveMessageSink
) : StoveKafkaObserverServiceWireGrpc.StoveKafkaObserverServiceImplBase() {
  private val logger: Logger = LoggerFactory.getLogger(javaClass)

  override suspend fun healthCheck(request: HealthCheckRequest): HealthCheckResponse {
    logger.info("Received health check request: $request")
    return HealthCheckResponse(status = HealthCheckResponse.ServingStatus.SERVING)
  }

  override suspend fun onPublishedMessage(request: PublishedMessage): Reply {
    logger.info("Received published message: $request")
    sink.onMessagePublished(request)
    return Reply(status = 200)
  }

  override suspend fun onConsumedMessage(request: ConsumedMessage): Reply {
    logger.info("Received consumed message: $request")
    sink.onMessageConsumed(request)
    return Reply(status = 200)
  }

  override suspend fun onCommittedMessage(request: CommittedMessage): Reply {
    logger.info("Received committed message: $request")
    sink.onMessageCommitted(request)
    return Reply(status = 200)
  }

  override suspend fun onAcknowledgedMessage(request: AcknowledgedMessage): Reply {
    logger.info("Received acknowledged message: $request")
    sink.onMessageAcknowledged(request)
    return Reply(status = 200)
  }
}
