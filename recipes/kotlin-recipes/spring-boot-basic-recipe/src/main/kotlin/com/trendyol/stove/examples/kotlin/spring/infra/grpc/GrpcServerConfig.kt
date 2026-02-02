package com.trendyol.stove.examples.kotlin.spring.infra.grpc

import io.github.oshai.kotlinlogging.KotlinLogging
import io.grpc.Server
import io.grpc.ServerBuilder
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Configuration

private val logger = KotlinLogging.logger {}

/**
 * Configuration for the gRPC server.
 *
 * Starts a gRPC server that exposes the OrderQueryService.
 */
@Configuration
class GrpcServerConfig(
  private val orderQueryGrpcService: OrderQueryGrpcService,
  @param:Value("\${grpc.server.port:50051}") private val port: Int
) {
  private lateinit var server: Server

  @PostConstruct
  fun start() {
    server = ServerBuilder.forPort(port)
      .addService(orderQueryGrpcService)
      .build()
      .start()

    logger.info { "gRPC server started on port $port" }
  }

  @PreDestroy
  fun stop() {
    server.shutdown()
    logger.info { "gRPC server stopped" }
  }
}
