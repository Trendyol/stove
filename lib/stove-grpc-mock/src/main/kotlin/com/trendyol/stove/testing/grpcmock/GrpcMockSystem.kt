@file:Suppress("unused", "MagicNumber", "TooManyFunctions")

package com.trendyol.stove.testing.grpcmock

import arrow.core.*
import com.google.protobuf.Message
import com.trendyol.stove.functional.*
import com.trendyol.stove.reporting.*
import com.trendyol.stove.scoping.TestScopeCleanupListener
import com.trendyol.stove.scoping.TestScopedJournal
import com.trendyol.stove.scoping.stoveTestId
import com.trendyol.stove.system.Stove
import com.trendyol.stove.system.abstractions.*
import io.grpc.*
import io.grpc.protobuf.services.HealthStatusManager
import io.grpc.protobuf.services.ProtoReflectionServiceV1
import io.grpc.stub.ServerCalls
import io.grpc.stub.StreamObserver
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.time.Duration

/**
 * Native gRPC mock server for testing gRPC service integrations.
 *
 * This implementation provides full support for all gRPC RPC types:
 * - Unary (request-response)
 * - Server streaming (single request, stream of responses)
 * - Client streaming (stream of requests, single response)
 * - Bidirectional streaming (stream of requests, stream of responses)
 *
 * ## Configuration
 *
 * ```kotlin
 * Stove()
 *   .with {
 *     grpcMock {
 *       GrpcMockSystemOptions(port = 9090)
 *     }
 *     grpc {
 *       GrpcSystemOptions(host = "localhost", port = 9090)
 *     }
 *   }
 * ```
 *
 * ## Mocking Unary Calls
 *
 * ```kotlin
 * stove {
 *   grpcMock {
 *     mockUnary(
 *       serviceName = "greeting.GreeterService",
 *       methodName = "SayHello",
 *       response = HelloResponse.newBuilder().setMessage("Hello!").build()
 *     )
 *   }
 * }
 * ```
 *
 * ## Mocking Authenticated Calls
 *
 * ```kotlin
 * stove {
 *   grpcMock {
 *     // Require specific bearer token
 *     mockUnary(
 *       serviceName = "secure.SecureService",
 *       methodName = "GetSecret",
 *       metadataMatcher = MetadataMatcher.BearerToken("valid-token"),
 *       response = SecretResponse.newBuilder().setData("secret").build()
 *     )
 *
 *     // Or use custom header matching
 *     mockUnary(
 *       serviceName = "secure.SecureService",
 *       methodName = "GetSecret",
 *       metadataMatcher = MetadataMatcher.HasHeader("x-api-key", "my-api-key"),
 *       response = SecretResponse.newBuilder().setData("secret").build()
 *     )
 *   }
 * }
 * ```
 */
@GrpcMockDsl
class GrpcMockSystem internal constructor(
  override val stove: Stove,
  private val ctx: GrpcMockContext
) : PluggedSystem,
  ValidatedSystem,
  RunAware,
  ExposesConfiguration,
  Reports {
  private val logger = LoggerFactory.getLogger(javaClass)
  override val reportSystemName: String = "gRPC Mock" + (ctx.keyName?.let { " [$it]" } ?: "")

  private val stubs = ConcurrentHashMap<String, CopyOnWriteArrayList<RegisteredStub>>()
  private val callJournal = TestScopedJournal<JournaledRequest>()
  private val reportListener = TestScopeCleanupListener(callJournal::clear)
  private var reportListenerRegistered = false
  private val handlerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  private lateinit var server: Server
  private lateinit var exposedConfiguration: GrpcMockExposedConfiguration

  override fun configuration(): List<String> = ctx.configureExposedConfiguration(exposedConfiguration)

  // ==================== Lifecycle ====================

  override suspend fun run() {
    if (!reportListenerRegistered) {
      stove.addReportListener(reportListener)
      reportListenerRegistered = true
    }
    val builder = ctx
      .serverBuilder(ServerBuilder.forPort(ctx.port))
      .intercept(MetadataCapturingInterceptor)
      .fallbackHandlerRegistry(DynamicHandlerRegistry())
    if (ctx.enableHealthService) builder.addService(HealthStatusManager().healthService)
    if (ctx.enableReflectionService) builder.addService(ProtoReflectionServiceV1.newInstance())
    server = builder
      .build()
      .also { it.start() }

    exposedConfiguration = GrpcMockExposedConfiguration(
      host = "localhost",
      port = server.port
    )
    logger.info("gRPC Mock server started on port ${server.port}")
  }

  override suspend fun stop() {
    if (::server.isInitialized) {
      server.shutdown().awaitTermination(5, TimeUnit.SECONDS)
      logger.info("gRPC Mock server stopped")
    }
  }

  override fun close(): Unit = runBlocking {
    Try {
      if (reportListenerRegistered) {
        stove.removeReportListener(reportListener)
        reportListenerRegistered = false
      }
      stop()
      handlerScope.cancel()
      callJournal.clearAll()
    }.recover { logger.warn("Error stopping gRPC mock: ${it.message}") }
  }

  // ==================== Stub Registration ====================

  /**
   * Mocks a unary RPC (single request → single response).
   *
   * @param serviceName The fully qualified gRPC service name (e.g., "greeting.GreeterService")
   * @param methodName The RPC method name (e.g., "SayHello")
   * @param requestMatcher Optional matcher for filtering requests. Defaults to [RequestMatcher.Any].
   * @param metadataMatcher Optional matcher for filtering by gRPC metadata/headers. Defaults to [MetadataMatcher.Any].
   *   Use [MetadataMatcher.BearerToken] for Bearer token auth, [MetadataMatcher.HasHeader] for custom headers.
   * @param response The protobuf [Message] to return when this stub is matched
   * @return This [GrpcMockSystem] instance for chaining
   *
   * @sample
   * ```kotlin
   * grpcMock {
   *   mockUnary(
   *     serviceName = "greeting.GreeterService",
   *     methodName = "SayHello",
   *     response = HelloResponse.newBuilder().setMessage("Hello!").build()
   *   )
   * }
   * ```
   */
  suspend fun mockUnary(
    serviceName: String,
    methodName: String,
    requestMatcher: RequestMatcher = RequestMatcher.Any,
    metadataMatcher: MetadataMatcher = MetadataMatcher.Any,
    response: Message,
    delay: Duration? = null
  ): GrpcMockSystem = registerStub(
    serviceName,
    methodName,
    StubDefinition.Unary(requestMatcher, metadataMatcher, response, delay),
    "unary"
  ) { response.toString().take(200).some() }

  /**
   * Mocks a server streaming RPC (single request → stream of responses).
   *
   * The mock will send all responses in sequence when a matching request is received.
   *
   * @param serviceName The fully qualified gRPC service name (e.g., "streaming.ItemService")
   * @param methodName The RPC method name (e.g., "ListItems")
   * @param requestMatcher Optional matcher for filtering requests. Defaults to [RequestMatcher.Any].
   * @param metadataMatcher Optional matcher for filtering by gRPC metadata/headers. Defaults to [MetadataMatcher.Any].
   * @param responses The list of protobuf [Message]s to stream back. Must not be empty.
   * @return This [GrpcMockSystem] instance for chaining
   * @throws IllegalArgumentException if [responses] is empty
   *
   * @sample
   * ```kotlin
   * grpcMock {
   *   mockServerStream(
   *     serviceName = "streaming.ItemService",
   *     methodName = "ListItems",
   *     responses = listOf(
   *       Item.newBuilder().setId("1").build(),
   *       Item.newBuilder().setId("2").build()
   *     )
   *   )
   * }
   * ```
   */
  suspend fun mockServerStream(
    serviceName: String,
    methodName: String,
    requestMatcher: RequestMatcher = RequestMatcher.Any,
    metadataMatcher: MetadataMatcher = MetadataMatcher.Any,
    responses: List<Message>,
    delay: Duration? = null,
    thenFailWith: Status? = null
  ): GrpcMockSystem {
    require(responses.isNotEmpty()) { "responses must not be empty" }
    return registerStub(
      serviceName,
      methodName,
      StubDefinition.ServerStream(requestMatcher, metadataMatcher, responses, delay, thenFailWith),
      "server stream",
      metadata = mapOf("responseCount" to responses.size)
    )
  }

  /**
   * Mocks a client streaming RPC (stream of requests → single response).
   *
   * The mock will collect all incoming requests and return the configured response
   * when the client completes the stream.
   *
   * **Note:** The [requestMatcher] is evaluated against **only the first request** in the stream,
   * because stub matching happens before the full stream is received. If you need to validate
   * all requests in a client stream, consider using [mockBidiStream] with a custom handler.
   *
   * @param serviceName The fully qualified gRPC service name (e.g., "upload.UploadService")
   * @param methodName The RPC method name (e.g., "UploadChunks")
   * @param requestMatcher Optional matcher for the first request in the stream. Defaults to [RequestMatcher.Any].
   * @param metadataMatcher Optional matcher for filtering by gRPC metadata/headers. Defaults to [MetadataMatcher.Any].
   * @param response The protobuf [Message] to return after the client completes streaming
   * @return This [GrpcMockSystem] instance for chaining
   *
   * @sample
   * ```kotlin
   * grpcMock {
   *   mockClientStream(
   *     serviceName = "upload.UploadService",
   *     methodName = "UploadChunks",
   *     response = UploadResponse.newBuilder().setSuccess(true).build()
   *   )
   * }
   * ```
   */
  suspend fun mockClientStream(
    serviceName: String,
    methodName: String,
    requestMatcher: RequestMatcher = RequestMatcher.Any,
    metadataMatcher: MetadataMatcher = MetadataMatcher.Any,
    response: Message,
    delay: Duration? = null
  ): GrpcMockSystem = registerStub(
    serviceName,
    methodName,
    StubDefinition.ClientStream(requestMatcher, metadataMatcher, response, delay),
    "client stream"
  ) { response.toString().take(200).some() }

  /**
   * Mocks a bidirectional streaming RPC (stream of requests ↔ stream of responses).
   *
   * The [handler] receives a flow of raw request bytes and should return a flow of response messages.
   * This allows full control over the streaming behavior, including transforming requests into responses.
   *
   * @param serviceName The fully qualified gRPC service name (e.g., "chat.ChatService")
   * @param methodName The RPC method name (e.g., "Chat")
   * @param requestMatcher Must be [RequestMatcher.Any]: bidi stubs are selected before any request
   *   message arrives, so request-based matching is impossible. Passing any other matcher fails fast
   *   instead of being silently ignored. Inspect requests inside the [handler].
   * @param metadataMatcher Optional matcher for filtering by gRPC metadata/headers. Defaults to [MetadataMatcher.Any].
   * @param handler A suspending function that transforms the incoming request flow into a response flow.
   *   The handler receives raw [ByteArray] request bytes which can be parsed using protobuf's `parseFrom`.
   * @return This [GrpcMockSystem] instance for chaining
   *
   * @sample
   * ```kotlin
   * grpcMock {
   *   mockBidiStream(
   *     serviceName = "chat.ChatService",
   *     methodName = "Chat"
   *   ) { requestFlow ->
   *     requestFlow.map { bytes ->
   *       val request = ChatMessage.parseFrom(bytes)
   *       ChatMessage.newBuilder()
   *         .setMessage("Echo: ${request.message}")
   *         .build()
   *     }
   *   }
   * }
   * ```
   */
  suspend fun mockBidiStream(
    serviceName: String,
    methodName: String,
    requestMatcher: RequestMatcher = RequestMatcher.Any,
    metadataMatcher: MetadataMatcher = MetadataMatcher.Any,
    handler: suspend (Flow<ByteArray>) -> Flow<Message>
  ): GrpcMockSystem {
    require(requestMatcher is RequestMatcher.Any) {
      "requestMatcher is not supported for bidi streams: the stub is selected before any request " +
        "message arrives. Use metadataMatcher, or inspect requests inside the handler."
    }
    return registerStub(
      serviceName,
      methodName,
      StubDefinition.BidiStream(requestMatcher, metadataMatcher, handler),
      "bidi stream"
    )
  }

  /**
   * Mocks a gRPC error response for any RPC type.
   *
   * When a matching request is received, the mock will respond with the specified gRPC error status.
   *
   * @param serviceName The fully qualified gRPC service name (e.g., "users.UserService")
   * @param methodName The RPC method name (e.g., "GetUser")
   * @param requestMatcher Optional matcher for filtering requests. Defaults to [RequestMatcher.Any].
   * @param metadataMatcher Optional matcher for filtering by gRPC metadata/headers. Defaults to [MetadataMatcher.Any].
   * @param status The gRPC [Status.Code] to return (e.g., NOT_FOUND, UNAUTHENTICATED, PERMISSION_DENIED)
   * @param message Optional error message description. Defaults to the status code name.
   * @return This [GrpcMockSystem] instance for chaining
   *
   * @sample
   * ```kotlin
   * grpcMock {
   *   mockError(
   *     serviceName = "users.UserService",
   *     methodName = "GetUser",
   *     status = Status.Code.NOT_FOUND,
   *     message = "User not found"
   *   )
   * }
   * ```
   */
  suspend fun mockError(
    serviceName: String,
    methodName: String,
    requestMatcher: RequestMatcher = RequestMatcher.Any,
    metadataMatcher: MetadataMatcher = MetadataMatcher.Any,
    status: Status.Code,
    message: String = status.name,
    trailers: Metadata? = null,
    delay: Duration? = null
  ): GrpcMockSystem = registerStub(
    serviceName,
    methodName,
    StubDefinition.Error(requestMatcher, metadataMatcher, Status.fromCode(status), message, trailers, delay),
    "error",
    metadata = mapOf("status" to status.name, "message" to message)
  )

  // ==================== Descriptor-typed Registration ====================

  /**
   * Mocks a unary RPC identified by its generated [MethodDescriptor] — compile-time safe,
   * no service/method name strings.
   *
   * ```kotlin
   * grpcMock {
   *   mockUnary(GreeterGrpc.getSayHelloMethod(), response = reply)
   * }
   * ```
   */
  suspend fun mockUnary(
    method: MethodDescriptor<*, *>,
    requestMatcher: RequestMatcher = RequestMatcher.Any,
    metadataMatcher: MetadataMatcher = MetadataMatcher.Any,
    response: Message,
    delay: Duration? = null
  ): GrpcMockSystem =
    mockUnary(method.requireServiceName(), method.requireBareMethodName(), requestMatcher, metadataMatcher, response, delay)

  /** Mocks a server-streaming RPC identified by its generated [MethodDescriptor]. */
  suspend fun mockServerStream(
    method: MethodDescriptor<*, *>,
    requestMatcher: RequestMatcher = RequestMatcher.Any,
    metadataMatcher: MetadataMatcher = MetadataMatcher.Any,
    responses: List<Message>,
    delay: Duration? = null,
    thenFailWith: Status? = null
  ): GrpcMockSystem =
    mockServerStream(
      method.requireServiceName(),
      method.requireBareMethodName(),
      requestMatcher,
      metadataMatcher,
      responses,
      delay,
      thenFailWith
    )

  /** Mocks a client-streaming RPC identified by its generated [MethodDescriptor]. */
  suspend fun mockClientStream(
    method: MethodDescriptor<*, *>,
    requestMatcher: RequestMatcher = RequestMatcher.Any,
    metadataMatcher: MetadataMatcher = MetadataMatcher.Any,
    response: Message,
    delay: Duration? = null
  ): GrpcMockSystem =
    mockClientStream(method.requireServiceName(), method.requireBareMethodName(), requestMatcher, metadataMatcher, response, delay)

  /** Mocks a bidi-streaming RPC identified by its generated [MethodDescriptor]. */
  suspend fun mockBidiStream(
    method: MethodDescriptor<*, *>,
    metadataMatcher: MetadataMatcher = MetadataMatcher.Any,
    handler: suspend (Flow<ByteArray>) -> Flow<Message>
  ): GrpcMockSystem =
    mockBidiStream(method.requireServiceName(), method.requireBareMethodName(), RequestMatcher.Any, metadataMatcher, handler)

  /** Mocks a gRPC error for the RPC identified by its generated [MethodDescriptor]. */
  suspend fun mockError(
    method: MethodDescriptor<*, *>,
    requestMatcher: RequestMatcher = RequestMatcher.Any,
    metadataMatcher: MetadataMatcher = MetadataMatcher.Any,
    status: Status.Code,
    message: String = status.name,
    trailers: Metadata? = null,
    delay: Duration? = null
  ): GrpcMockSystem =
    mockError(
      method.requireServiceName(),
      method.requireBareMethodName(),
      requestMatcher,
      metadataMatcher,
      status,
      message,
      trailers,
      delay
    )

  private fun MethodDescriptor<*, *>.requireServiceName(): String =
    requireNotNull(serviceName) { "MethodDescriptor has no service name: $fullMethodName" }

  private fun MethodDescriptor<*, *>.requireBareMethodName(): String =
    requireNotNull(bareMethodName) { "MethodDescriptor has no method name: $fullMethodName" }

  // ==================== Typed Verification ====================

  /**
   * Verifies that the mock received exactly [times] requests of type [T] on the given method
   * satisfying [condition], scoped to the current test. Point-in-time: by the moment mock
   * assertions run, the call either happened or it didn't — there is nothing to wait for.
   *
   * Both matched and unmatched requests count: the assertion is about what the application
   * sent, not about stub bookkeeping.
   *
   * ```kotlin
   * grpcMock {
   *   shouldHaveBeenCalled<GetUserRequest>("users.UserService", "GetUser") { it.userId == "123" }
   * }
   * ```
   */
  suspend inline fun <reified T : Message> shouldHaveBeenCalled(
    serviceName: String,
    methodName: String,
    times: Int = 1,
    noinline condition: (T) -> Boolean = { true }
  ): GrpcMockSystem =
    verifyCallCount(serviceName, methodName, times, ProtoPayloads.parserFor(T::class.java), condition)

  /** Descriptor-typed variant of [shouldHaveBeenCalled]. */
  suspend inline fun <reified T : Message> shouldHaveBeenCalled(
    method: MethodDescriptor<*, *>,
    times: Int = 1,
    noinline condition: (T) -> Boolean = { true }
  ): GrpcMockSystem =
    verifyCallCount(
      requireNotNull(method.serviceName),
      requireNotNull(method.bareMethodName),
      times,
      ProtoPayloads.parserFor(T::class.java),
      condition
    )

  /**
   * Verifies that no request of type [T] satisfying [condition] reached the given method in
   * this test. Sound for mocks: the mock server is the endpoint itself, so absence of evidence
   * here is evidence of absence.
   */
  suspend inline fun <reified T : Message> shouldNotHaveBeenCalled(
    serviceName: String,
    methodName: String,
    noinline condition: (T) -> Boolean = { true }
  ): GrpcMockSystem =
    verifyCallCount(serviceName, methodName, 0, ProtoPayloads.parserFor(T::class.java), condition)

  /** Descriptor-typed variant of [shouldNotHaveBeenCalled]. */
  suspend inline fun <reified T : Message> shouldNotHaveBeenCalled(
    method: MethodDescriptor<*, *>,
    noinline condition: (T) -> Boolean = { true }
  ): GrpcMockSystem =
    verifyCallCount(
      requireNotNull(method.serviceName),
      requireNotNull(method.bareMethodName),
      0,
      ProtoPayloads.parserFor(T::class.java),
      condition
    )

  @PublishedApi
  internal suspend fun <T : Message> verifyCallCount(
    serviceName: String,
    methodName: String,
    times: Int,
    parser: (ByteArray) -> T?,
    condition: (T) -> Boolean
  ): GrpcMockSystem {
    val fullMethodName = "$serviceName/$methodName"
    val received = callJournal
      .entries(reporter.currentTestId())
      .map { it.request }
      .filter { it.stubKey.fullMethodName == fullMethodName }
    val matching = received.filter { request -> parser(request.requestBytes)?.let(condition) == true }

    report(
      action = "Verify called exactly $times time(s): $fullMethodName",
      expected = "$times matching request(s)".some(),
      actual = "${matching.size} matching request(s)".some()
    ) {
      if (matching.size != times) {
        throw AssertionError(
          "Expected exactly $times request(s) matching the condition on $fullMethodName, " +
            "but found ${matching.size} of ${received.size} request(s) this test sent to the method." +
            received.render(parser)
        )
      }
    }
    return this
  }

  private fun <T : Message> List<ReceivedRequest>.render(parser: (ByteArray) -> T?): String {
    if (isEmpty()) return ""
    return "\nReceived on this method (this test):\n" + take(MAX_RENDERED_REQUESTS).joinToString("\n") { request ->
      val payload = parser(request.requestBytes)?.let { with(ProtoPayloads) { it.singleLine() } }
      "  - { ${payload ?: "unparseable as expected type"} }"
    } + if (size > MAX_RENDERED_REQUESTS) "\n  … and ${size - MAX_RENDERED_REQUESTS} more" else ""
  }

  // ==================== Validation & Reporting ====================

  override suspend fun validate() {
    val unmatched = callJournal
      .entries(reporter.currentTestId())
      .filter { !it.request.matched }

    if (unmatched.isNotEmpty()) {
      val error = AssertionError(
        "There are ${unmatched.size} unmatched gRPC requests:\n" +
          unmatched.joinToString("\n") { journaled ->
            "  - ${journaled.request.stubKey.fullMethodName}" +
              journaled.nearMisses.joinToString("") { "\n      $it" }
          }
      )
      reporter.record(
        ReportEntry.failure(
          system = reportSystemName,
          testId = reporter.currentTestId(),
          action = "Validate: All gRPC requests should match registered stubs",
          error = error.message.orEmpty(),
          expected = "0 unmatched requests".some(),
          actual = "${unmatched.size} unmatched request(s)".some()
        )
      )
      throw error
    }

    reporter.record(
      ReportEntry.success(
        system = reportSystemName,
        testId = reporter.currentTestId(),
        action = "Validate: All gRPC requests matched registered stubs"
      )
    )
  }

  override fun then(): Stove = stove

  override fun snapshot(): SystemSnapshot {
    val currentTestId = reporter.currentTestId()
    val scopedStubs = stubs.values
      .flatten()
      .filter { it.testId == null || it.testId == currentTestId }
    val scopedRequests = callJournal.entries(currentTestId).map { it.request }

    return SystemSnapshot(
      system = reportSystemName,
      state = mapOf(
        "registeredStubs" to scopedStubs.map { registered ->
          mapOf(
            "id" to registered.key.id,
            "service" to registered.key.serviceName,
            "method" to registered.key.methodName,
            "type" to registered.definition::class.simpleName
          )
        },
        "receivedRequests" to scopedRequests.map { req ->
          mapOf(
            "method" to req.stubKey.fullMethodName,
            "matched" to req.matched,
            "timestamp" to req.timestamp,
            "hasAuth" to (req.authorizationHeader != null)
          )
        }
      ),
      summary = """
        |Registered stubs: ${scopedStubs.size}
        |Received requests: ${scopedRequests.size}
        |Matched requests: ${scopedRequests.count { it.matched }}
        |Unmatched requests: ${scopedRequests.count { !it.matched }}
        |Authenticated requests: ${scopedRequests.count { it.authorizationHeader != null }}
      """.trimMargin()
    )
  }

  // ==================== Internal: Registration ====================

  private suspend fun registerStub(
    serviceName: String,
    methodName: String,
    stub: StubDefinition,
    stubType: String,
    metadata: Map<String, Any> = emptyMap(),
    outputProvider: () -> Option<String> = { None }
  ): GrpcMockSystem {
    val key = StubKey(serviceName, methodName)
    val authInfo = when (val matcher = stub.metadataMatcher) {
      is MetadataMatcher.BearerToken -> " (authenticated)"
      is MetadataMatcher.RequiresAuth -> " (requires auth)"
      is MetadataMatcher.HasHeader -> " (header: ${matcher.key})"
      else -> ""
    }
    report(
      action = "Register $stubType stub: $serviceName/$methodName$authInfo",
      output = outputProvider(),
      metadata = metadata
    ) {
      val registered = stubs.computeIfAbsent(key.fullMethodName) { CopyOnWriteArrayList() }
      // Error stubs adapt to any method type, so they never conflict.
      val conflicting = registered.firstOrNull {
        stub !is StubDefinition.Error &&
          it.definition !is StubDefinition.Error &&
          it.definition.methodType != stub.methodType
      }
      require(conflicting == null) {
        "Cannot register a ${stub.methodType} stub for ${key.fullMethodName}: " +
          "a ${conflicting?.definition?.methodType} stub already exists for this method. " +
          "A gRPC method has exactly one type; register stubs of one type per method."
      }
      registered.add(RegisteredStub(key, stub, reporter.currentTestIdOrNull()))
      logger.debug("Registered stub for ${key.fullMethodName} (id: ${key.id})")
    }
    return this
  }

  // ==================== Internal: Stub Lookup ====================

  private fun findAndProcessStub(
    fullMethodName: String,
    requestBytes: ByteArray,
    metadata: Metadata
  ): Option<RegisteredStub> {
    val stubKey = fullMethodName.toStubKey()
    ctx.onRequestReceived(stubKey, requestBytes)

    // Last-registered wins: test-local stubs override earlier fixture defaults.
    return stubs[fullMethodName]
      ?.findLast { registered ->
        registered.definition.requestMatcher.matches(requestBytes) &&
          registered.definition.metadataMatcher.matches(metadata)
      }?.also { registered ->
        logRequest(registered.key, requestBytes, metadata, matched = true, stubTestId = registered.testId)
        removeStubIfNeeded(registered.key, fullMethodName)
        ctx.afterStubMatched(registered.key, registered.definition)
      }.toOption()
      .onNone {
        logRequest(
          stubKey,
          requestBytes,
          metadata,
          matched = false,
          nearMisses = (stubs[fullMethodName] ?: emptyList()).diagnoseRejections(requestBytes, metadata)
        )
      }
  }

  private fun removeStubIfNeeded(key: StubKey, fullMethodName: String) {
    if (ctx.removeStubAfterRequestMatched) {
      stubs[fullMethodName]?.removeIf { it.key.id == key.id }
      logger.debug("Removed stub ${key.id} after match")
    }
  }

  private fun logRequest(
    key: StubKey,
    requestBytes: ByteArray,
    metadata: Metadata,
    matched: Boolean,
    stubTestId: String? = null,
    nearMisses: List<String> = emptyList()
  ) {
    val request = ReceivedRequest(
      stubKey = key,
      requestBytes = requestBytes,
      metadata = metadata,
      matched = matched,
      stubId = if (matched) key.id else null,
      testId = metadata.toHeaderMap().stoveTestId() ?: stubTestId
    )
    callJournal.record(request.testId, JournaledRequest(request, nearMisses))
  }

  // ==================== Internal: Handler Registry ====================

  private inner class DynamicHandlerRegistry : HandlerRegistry() {
    override fun lookupMethod(methodName: String, authority: String?): ServerMethodDefinition<*, *>? {
      logger.debug("Looking up method: $methodName")
      // Error stubs are type-agnostic; the method's real type comes from any other stub.
      return stubs[methodName]
        ?.let { registered ->
          registered.firstOrNull { it.definition !is StubDefinition.Error } ?: registered.firstOrNull()
        }?.let { registered ->
          createHandler(methodName, registered.definition.methodType)
        } ?: unknownMethodDefinition(methodName)
    }
  }

  /**
   * Handler for methods with no stubs at all. Returning null here would let gRPC answer
   * UNIMPLEMENTED before any Stove code runs, leaving the request invisible to the journal —
   * a typo'd method name would never show up in validate(), snapshots, or diagnostics.
   * The client still receives UNIMPLEMENTED immediately, exactly as before; request payloads
   * are not consumed, so the journaled request carries method and metadata but no bytes.
   */
  private fun unknownMethodDefinition(fullMethodName: String): ServerMethodDefinition<ByteArray, ByteArray>? {
    val stubKey = runCatching { fullMethodName.toStubKey() }.getOrNull() ?: return null
    logger.warn("No stub registered for method: $fullMethodName")
    // BIDI accepts any client message pattern, so this handler is safe for every real method type.
    val method = MethodDescriptor
      .newBuilder<ByteArray, ByteArray>()
      .setType(MethodDescriptor.MethodType.BIDI_STREAMING)
      .setFullMethodName(fullMethodName)
      .setRequestMarshaller(ByteArrayMarshaller)
      .setResponseMarshaller(ByteArrayMarshaller)
      .build()
    val handler = ServerCalls.asyncBidiStreamingCall<ByteArray, ByteArray> { responseObserver ->
      val metadata = MetadataCapturingInterceptor.currentMetadata()
      ctx.onRequestReceived(stubKey, ByteArray(0))
      logRequest(
        stubKey,
        ByteArray(0),
        metadata,
        matched = false,
        nearMisses = emptyList<RegisteredStub>().diagnoseRejections(ByteArray(0), metadata)
      )
      responseObserver.sendUnimplemented(fullMethodName)
      DiscardingStreamObserver
    }
    return ServerMethodDefinition.create(method, handler)
  }

  private fun createHandler(
    fullMethodName: String,
    methodType: MethodDescriptor.MethodType
  ): ServerMethodDefinition<ByteArray, ByteArray> {
    val method = MethodDescriptor
      .newBuilder<ByteArray, ByteArray>()
      .setType(methodType)
      .setFullMethodName(fullMethodName)
      .setRequestMarshaller(ByteArrayMarshaller)
      .setResponseMarshaller(ByteArrayMarshaller)
      .build()

    val handler = when (methodType) {
      MethodDescriptor.MethodType.UNARY -> unaryHandler(fullMethodName)
      MethodDescriptor.MethodType.SERVER_STREAMING -> serverStreamHandler(fullMethodName)
      MethodDescriptor.MethodType.CLIENT_STREAMING -> clientStreamHandler(fullMethodName)
      MethodDescriptor.MethodType.BIDI_STREAMING -> bidiStreamHandler(fullMethodName)
      else -> unaryHandler(fullMethodName)
    }

    return ServerMethodDefinition.create(method, handler)
  }

  // ==================== Internal: Call Handlers ====================

  private fun unaryHandler(fullMethodName: String): ServerCallHandler<ByteArray, ByteArray> =
    ServerCalls.asyncUnaryCall { request, observer ->
      val metadata = MetadataCapturingInterceptor.currentMetadata()
      findAndProcessStub(fullMethodName, request, metadata).fold(
        ifEmpty = { observer.sendUnimplemented(fullMethodName) },
        ifSome = { registered -> registered.definition.sendResponse(observer) }
      )
    }

  private fun serverStreamHandler(fullMethodName: String): ServerCallHandler<ByteArray, ByteArray> =
    ServerCalls.asyncServerStreamingCall { request, observer ->
      val metadata = MetadataCapturingInterceptor.currentMetadata()
      findAndProcessStub(fullMethodName, request, metadata).fold(
        ifEmpty = { observer.sendUnimplemented(fullMethodName) },
        ifSome = { registered -> registered.definition.sendResponse(observer) }
      )
    }

  private fun clientStreamHandler(fullMethodName: String): ServerCallHandler<ByteArray, ByteArray> =
    ServerCalls.asyncClientStreamingCall { responseObserver ->
      val metadata = MetadataCapturingInterceptor.currentMetadata()
      CollectingStreamObserver(
        onComplete = { requests ->
          val requestBytes = requests.firstOrNull() ?: ByteArray(0)
          findAndProcessStub(fullMethodName, requestBytes, metadata).fold(
            ifEmpty = { responseObserver.sendUnimplemented(fullMethodName) },
            ifSome = { registered -> registered.definition.sendResponse(responseObserver) }
          )
        },
        onStreamError = { responseObserver.onError(it) }
      )
    }

  private fun bidiStreamHandler(fullMethodName: String): ServerCallHandler<ByteArray, ByteArray> =
    ServerCalls.asyncBidiStreamingCall { responseObserver ->
      val metadata = MetadataCapturingInterceptor.currentMetadata()
      val requestChannel = Channel<ByteArray>(Channel.UNLIMITED)

      handlerScope.launch {
        stubs[fullMethodName]?.findLast { it.definition.metadataMatcher.matches(metadata) }?.let { registered ->
          when (val stub = registered.definition) {
            is StubDefinition.BidiStream -> runCatching {
              stub.handler(requestChannel.consumeAsFlow()).collect { response ->
                responseObserver.onNext(response.toByteArray())
              }
              responseObserver.onCompleted()
            }.onFailure { e ->
              responseObserver.onError(Status.INTERNAL.withCause(e).asException())
            }

            is StubDefinition.Error -> responseObserver.onError(stub.toStatusException())

            else -> responseObserver.sendUnexpectedStubType("bidi stream")
          }
        } ?: responseObserver.sendUnimplemented(fullMethodName)
      }

      ChannelForwardingObserver(requestChannel)
    }

  // ==================== Extension Functions ====================

  private fun StubDefinition.sendResponse(observer: StreamObserver<ByteArray>) {
    val delay = when (this) {
      is StubDefinition.Unary -> delay
      is StubDefinition.ServerStream -> delay
      is StubDefinition.ClientStream -> delay
      is StubDefinition.Error -> delay
      is StubDefinition.BidiStream -> null
    }
    if (delay == null) {
      dispatchResponse(observer)
    } else {
      handlerScope.launch {
        delay(delay)
        dispatchResponse(observer)
      }
    }
  }

  private fun StubDefinition.dispatchResponse(observer: StreamObserver<ByteArray>) {
    when (this) {
      is StubDefinition.Unary -> observer.sendSingleAndComplete(response.toByteArray())
      is StubDefinition.ServerStream -> {
        responses.forEach { observer.onNext(it.toByteArray()) }
        thenFailWith?.let { observer.onError(it.asException()) } ?: observer.onCompleted()
      }

      is StubDefinition.ClientStream -> observer.sendSingleAndComplete(response.toByteArray())
      is StubDefinition.Error -> observer.onError(toStatusException())
      is StubDefinition.BidiStream -> observer.sendUnexpectedStubType("non-bidi call")
    }
  }

  private fun StubDefinition.Error.toStatusException(): StatusException {
    val describedStatus = message?.let { status.withDescription(it) } ?: status
    return trailers?.let { StatusException(describedStatus, it) } ?: describedStatus.asException()
  }

  private fun StreamObserver<ByteArray>.sendSingleAndComplete(bytes: ByteArray) {
    onNext(bytes)
    onCompleted()
  }

  private fun StreamObserver<*>.sendUnimplemented(fullMethodName: String) {
    onError(Status.UNIMPLEMENTED.withDescription("No matching stub for $fullMethodName").asException())
  }

  private fun StreamObserver<*>.sendUnexpectedStubType(context: String) {
    onError(Status.INTERNAL.withDescription("Unexpected stub type for $context").asException())
  }

  private fun String.toStubKey(): StubKey {
    val parts = split("/")
    require(parts.size >= 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
      "Invalid gRPC method name format: '$this'. Expected format: 'serviceName/methodName'"
    }
    return StubKey(parts[0], parts[1])
  }

  // ==================== Helper Classes ====================

  private class CollectingStreamObserver(
    private val onComplete: (List<ByteArray>) -> Unit,
    private val onStreamError: (Throwable) -> Unit
  ) : StreamObserver<ByteArray> {
    private val collected = mutableListOf<ByteArray>()

    override fun onNext(value: ByteArray) {
      collected.add(value)
    }

    override fun onError(t: Throwable) {
      onStreamError(t)
    }

    override fun onCompleted() {
      onComplete(collected)
    }
  }

  /** Sink for messages arriving after the call has already been closed with an error. */
  private object DiscardingStreamObserver : StreamObserver<ByteArray> {
    override fun onNext(value: ByteArray) = Unit

    override fun onError(t: Throwable) = Unit

    override fun onCompleted() = Unit
  }

  private class ChannelForwardingObserver(
    private val channel: Channel<ByteArray>
  ) : StreamObserver<ByteArray> {
    override fun onNext(value: ByteArray) {
      // The channel is UNLIMITED, so trySend only fails when the channel is
      // already closed — in which case the value has nowhere to go anyway.
      channel.trySend(value)
    }

    override fun onError(t: Throwable) {
      channel.close(t)
    }

    override fun onCompleted() {
      channel.close()
    }
  }

  companion object {
    private const val MAX_RENDERED_REQUESTS = 5

    fun GrpcMockSystem.server(): Server = server
  }
}

/**
 * Interceptor that captures request metadata and makes it available via Context.
 */
private object MetadataCapturingInterceptor : ServerInterceptor {
  private val METADATA_KEY: Context.Key<Metadata> = Context.key("captured-metadata")

  fun currentMetadata(): Metadata = METADATA_KEY.get() ?: Metadata()

  override fun <ReqT, RespT> interceptCall(
    call: ServerCall<ReqT, RespT>,
    headers: Metadata,
    next: ServerCallHandler<ReqT, RespT>
  ): ServerCall.Listener<ReqT> {
    val context = Context.current().withValue(METADATA_KEY, headers)
    return Contexts.interceptCall(context, call, headers, next)
  }
}

private object ByteArrayMarshaller : MethodDescriptor.Marshaller<ByteArray> {
  override fun stream(value: ByteArray): InputStream = ByteArrayInputStream(value)

  override fun parse(stream: InputStream): ByteArray = stream.readBytes()
}
