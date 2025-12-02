package com.trendyol.stove.testing.e2e.grpc

import com.trendyol.stove.testing.e2e.grpc.test.*
import io.grpc.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit

/**
 * Test gRPC server for testing the GrpcSystem.
 *
 * Implements all RPC types: unary, server streaming, client streaming, and bidirectional streaming.
 */
class TestGrpcServer(
  private val port: Int
) : AutoCloseable {
  private val logger = LoggerFactory.getLogger(javaClass)
  private lateinit var server: Server

  fun start(): TestGrpcServer {
    server = ServerBuilder
      .forPort(port)
      .addService(TestServiceImpl())
      .intercept(AuthorizationServerInterceptor())
      .build()
      .start()

    logger.info("Test gRPC server started on port $port")
    return this
  }

  fun awaitTermination() {
    server.awaitTermination()
  }

  override fun close() {
    logger.info("Shutting down test gRPC server")
    server.shutdown()
    server.awaitTermination(5, TimeUnit.SECONDS)
  }
}

/**
 * Implementation of the TestService for testing purposes.
 */
class TestServiceImpl : TestServiceWireGrpc.TestServiceImplBase() {
  override suspend fun Unary(request: TestRequest): TestResponse = TestResponse(
    message = "Echo: ${request.message}",
    count = request.count,
    success = true
  )

  override fun ServerStream(request: TestRequest): Flow<TestResponse> = flow {
    repeat(request.count) { i ->
      emit(
        TestResponse(
          message = "${request.message} - Item $i",
          count = i,
          success = true
        )
      )
      delay(10) // Small delay to simulate streaming
    }
  }

  override suspend fun ClientStream(request: Flow<TestRequest>): TestResponse {
    var totalCount = 0
    val messages = mutableListOf<String>()

    request.collect { req ->
      totalCount += req.count
      messages.add(req.message)
    }

    return TestResponse(
      message = "Received: ${messages.joinToString(", ")}",
      count = totalCount,
      success = true
    )
  }

  override fun BidiStream(request: Flow<TestRequest>): Flow<TestResponse> = flow {
    request.collect { req ->
      emit(
        TestResponse(
          message = "Echo: ${req.message}",
          count = req.count,
          success = true
        )
      )
    }
  }

  override suspend fun AuthenticatedCall(request: TestRequest): TestResponse {
    val authToken = AUTH_TOKEN_KEY.get()
    return if (authToken != null && authToken.startsWith("Bearer ")) {
      TestResponse(
        message = "Authenticated: ${request.message}",
        count = request.count,
        success = true
      )
    } else {
      throw StatusException(Status.UNAUTHENTICATED.withDescription("Missing or invalid authorization"))
    }
  }

  companion object {
    val AUTH_TOKEN_KEY: Context.Key<String> = Context.key("auth-token")
  }
}

/**
 * Server interceptor that extracts authorization header and stores in context.
 */
class AuthorizationServerInterceptor : ServerInterceptor {
  override fun <ReqT, RespT> interceptCall(
    call: ServerCall<ReqT, RespT>,
    headers: Metadata,
    next: ServerCallHandler<ReqT, RespT>
  ): ServerCall.Listener<ReqT> {
    val authHeader = headers.get(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER))
    val context = if (authHeader != null) {
      Context.current().withValue(TestServiceImpl.AUTH_TOKEN_KEY, authHeader)
    } else {
      Context.current()
    }
    return Contexts.interceptCall(context, call, headers, next)
  }
}
