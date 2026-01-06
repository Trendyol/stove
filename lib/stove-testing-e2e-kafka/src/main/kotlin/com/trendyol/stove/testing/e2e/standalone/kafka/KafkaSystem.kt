@file:Suppress("TooGenericExceptionCaught")

package com.trendyol.stove.testing.e2e.standalone.kafka

import arrow.core.*
import com.trendyol.stove.functional.*
import com.trendyol.stove.testing.e2e.messaging.*
import com.trendyol.stove.testing.e2e.reporting.*
import com.trendyol.stove.testing.e2e.serialization.StoveSerde
import com.trendyol.stove.testing.e2e.standalone.kafka.intercepting.*
import com.trendyol.stove.testing.e2e.system.*
import com.trendyol.stove.testing.e2e.system.abstractions.*
import com.trendyol.stove.testing.e2e.system.annotations.StoveDsl
import io.github.embeddedkafka.*
import io.grpc.Server
import io.grpc.netty.NettyServerBuilder
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.selects.*
import org.apache.kafka.clients.admin.*
import org.apache.kafka.clients.consumer.*
import org.apache.kafka.clients.producer.*
import org.apache.kafka.common.serialization.*
import org.slf4j.*
import scala.collection.immutable.`Map$`
import java.net.*
import java.util.*
import kotlin.reflect.KClass
import kotlin.time.*
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

var stoveSerdeRef: StoveSerde<Any, ByteArray> = StoveSerde.jackson.anyByteArraySerde()

/**
 * Default port for the Stove Kafka Bridge gRPC server.
 * This can be overridden by setting the [STOVE_KAFKA_BRIDGE_PORT] environment variable
 * or by using [PortFinder.findAvailablePortAsString] to get a dynamically available port.
 */
var stoveKafkaBridgePortDefault: String = PortFinder.findAvailablePortAsString()
const val STOVE_KAFKA_BRIDGE_PORT = "STOVE_KAFKA_BRIDGE_PORT"
internal val StoveKafkaCoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

/**
 * Kafka messaging system for testing message publishing and consumption.
 *
 * Provides a comprehensive DSL for testing Kafka-based messaging patterns:
 * - Publishing messages to topics
 * - Asserting messages are consumed by the application
 * - Asserting messages are published by the application
 * - Asserting message processing failures
 *
 * ## Publishing Messages
 *
 * ```kotlin
 * kafka {
 *     // Publish a message to a topic
 *     publish("orders.created", OrderCreatedEvent(orderId = "123", amount = 99.99))
 *
 *     // Publish with custom headers
 *     publish(
 *         topic = "orders.created",
 *         message = event,
 *         headers = mapOf("correlationId" to "abc-123")
 *     )
 *
 *     // Publish with specific key
 *     publish(
 *         topic = "orders.created",
 *         key = "customer-456",
 *         message = event
 *     )
 * }
 * ```
 *
 * ## Asserting Consumed Messages
 *
 * Verify your application consumed messages correctly:
 *
 * ```kotlin
 * kafka {
 *     publish("orders.created", OrderCreatedEvent(orderId = "123"))
 *
 *     // Assert the message was consumed
 *     shouldBeConsumed<OrderCreatedEvent> {
 *         actual.orderId == "123"
 *     }
 *
 *     // With custom timeout
 *     shouldBeConsumed<OrderCreatedEvent>(atLeastIn = 30.seconds) {
 *         actual.orderId == "123"
 *     }
 * }
 * ```
 *
 * ## Asserting Published Messages
 *
 * Verify your application published messages:
 *
 * ```kotlin
 * // Trigger action that publishes a message
 * http {
 *     postAndExpectBodilessResponse("/orders", body = request.some()) {
 *         it.status shouldBe 201
 *     }
 * }
 *
 * kafka {
 *     // Assert message was published
 *     shouldBePublished<OrderConfirmedEvent> {
 *         actual.orderId == request.id
 *     }
 *
 *     // Assert with header validation
 *     shouldBePublished<OrderConfirmedEvent> {
 *         actual.orderId == request.id &&
 *         metadata.headers["X-Correlation-Id"] == correlationId
 *     }
 * }
 * ```
 *
 * ## Asserting Failed Messages
 *
 * Verify messages failed processing (for error handling tests):
 *
 * ```kotlin
 * kafka {
 *     publish("orders.created", InvalidOrderEvent(orderId = "invalid"))
 *
 *     shouldBeFailed<InvalidOrderEvent> {
 *         actual.orderId == "invalid"
 *     }
 * }
 * ```
 *
 * ## Topic Management
 *
 * ```kotlin
 * kafka {
 *     // Create topics
 *     createTopics("orders.created", "orders.confirmed")
 *
 *     // Delete topics
 *     deleteTopics("orders.created")
 * }
 * ```
 *
 * ## Configuration
 *
 * ```kotlin
 * TestSystem()
 *     .with {
 *         kafka {
 *             stoveKafkaObjectMapperRef = myObjectMapper
 *             KafkaSystemOptions {
 *                 listOf(
 *                     "spring.kafka.bootstrap-servers=${it.bootstrapServers}",
 *                     "spring.kafka.consumer.group-id=test-group"
 *                 )
 *             }
 *         }
 *     }
 * ```
 *
 * @property testSystem The parent test system.
 * @see KafkaSystemOptions
 * @see KafkaExposedConfiguration
 */
@Suppress("TooManyFunctions", "unused", "MagicNumber")
@StoveDsl
class KafkaSystem(
  override val testSystem: TestSystem,
  private val context: KafkaContext
) : PluggedSystem,
  ExposesConfiguration,
  RunAware,
  AfterRunAware,
  BeforeRunAware,
  Reports {
  override fun snapshot(): SystemSnapshot {
    val store = sink.store
    return SystemSnapshot(
      system = reportSystemName,
      state = mapOf<String, Any>(
        "consumed" to store.consumedMessages().map { it.toReportMap() },
        "published" to store.publishedMessages().map { it.toReportMap() },
        "committed" to store.committedMessages().map { it.toReportMap() }
      ),
      summary = """
        Consumed: ${store.consumedMessages().size}
        Published: ${store.publishedMessages().size}
        Committed: ${store.committedMessages().size}
      """.trimIndent()
    )
  }

  private fun ConsumedMessage.toReportMap(): Map<String, Any> = mapOf(
    "id" to id,
    "topic" to topic,
    "key" to key,
    "partition" to partition,
    "offset" to offset,
    "headers" to headers,
    "message" to String(message.toByteArray())
  )

  private fun PublishedMessage.toReportMap(): Map<String, Any> = mapOf(
    "id" to id,
    "topic" to topic,
    "key" to key,
    "headers" to headers,
    "message" to String(message.toByteArray())
  )

  private fun CommittedMessage.toReportMap(): Map<String, Any> = mapOf<String, Any>(
    "topic" to topic,
    "partition" to partition,
    "offset" to offset
  )

  private lateinit var exposedConfiguration: KafkaExposedConfiguration
  private lateinit var adminClient: Admin
  private lateinit var kafkaPublisher: KafkaProducer<String, Any>
  private lateinit var grpcServer: Server

  @PublishedApi
  internal lateinit var sink: TestSystemMessageSink
  private val logger: Logger = LoggerFactory.getLogger(javaClass)
  private val state: StateStorage<KafkaExposedConfiguration> =
    testSystem.options.createStateStorage<KafkaExposedConfiguration, KafkaSystem>()

  override suspend fun beforeRun() {
    stoveSerdeRef = context.options.serde
  }

  override suspend fun run() {
    exposedConfiguration = obtainExposedConfiguration()
    adminClient = createAdminClient(exposedConfiguration)
    kafkaPublisher = createPublisher(exposedConfiguration)
    sink = TestSystemMessageSink(adminClient, context.options.serde, context.options.topicSuffixes)
    grpcServer = startGrpcServer()
    runMigrationsIfNeeded()
  }

  override suspend fun afterRun() = Unit

  private suspend fun runMigrationsIfNeeded() {
    if (shouldRunMigrations()) {
      context.options.migrationCollection.run(KafkaMigrationContext(adminClient, context.options))
    }
  }

  private fun shouldRunMigrations(): Boolean = when {
    context.options is ProvidedKafkaSystemOptions -> context.options.runMigrations
    context.runtime is StoveKafkaContainer -> !state.isSubsequentRun() || testSystem.options.runMigrationsAlways
    context.runtime is EmbeddedKafkaRuntime -> !state.isSubsequentRun() || testSystem.options.runMigrationsAlways
    else -> throw UnsupportedOperationException("Unsupported runtime type: ${context.runtime::class}")
  }

  override suspend fun stop() {
    when (val runtime = context.runtime) {
      is ProvidedRuntime -> Unit
      is EmbeddedKafkaRuntime -> stopEmbeddedKafka()
      is StoveKafkaContainer -> runtime.stop()
      else -> throw UnsupportedOperationException("Unsupported runtime type: ${runtime::class}")
    }
  }

  override fun close(): Unit = runBlocking {
    Try {
      context.options.cleanup(adminClient)
      grpcServer.shutdownNow()
      StoveKafkaCoroutineScope.cancel()
      kafkaPublisher.close()
      executeWithReuseCheck { stop() }
    }
  }.recover { logger.warn("got an error while stopping: ${it.message}") }.let { }

  override fun configuration(): List<String> = context.options.configureExposedConfiguration(exposedConfiguration)

  @StoveDsl
  suspend fun publish(
    topic: String,
    message: Any,
    key: Option<String> = None,
    headers: Map<String, String> = mapOf(),
    partition: Int = 0,
    testCase: Option<String> = None
  ): KafkaSystem {
    val record = ProducerRecord<String, Any>(topic, partition, key.getOrNull(), message)
    headers.forEach { (k, v) -> record.headers().add(k, v.toByteArray()) }
    testCase.map { record.headers().add("testCase", it.toByteArray()) }
    kafkaPublisher.dispatch(record)

    recordSuccess(
      action = "Publish to '$topic'",
      input = arrow.core.Some(message),
      metadata = buildMap {
        key.onSome { put("key", it) }
        put("headers", headers)
        put("partition", partition)
      }
    )
    return this
  }

  @StoveDsl
  suspend inline fun <reified T : Any> shouldBeConsumed(
    atLeastIn: Duration = 5.seconds,
    crossinline condition: ObservedMessage<T>.() -> Boolean
  ): KafkaSystem = assertKafkaMessage(
    assertionName = "shouldBeConsumed",
    typeName = T::class.simpleName ?: "Unknown",
    timeout = atLeastIn,
    expected = "Message matching condition within $atLeastIn"
  ) { onMatch ->
    shouldBeConsumedInternal(T::class, atLeastIn) { parsed ->
      parsed.message.isSome { o ->
        val result = condition(ObservedMessage(o, parsed.metadata))
        if (result) onMatch(o)
        result
      }
    }
  }

  @StoveDsl
  suspend inline fun <reified T : Any> shouldBePublished(
    atLeastIn: Duration = 5.seconds,
    crossinline condition: ObservedMessage<T>.() -> Boolean
  ): KafkaSystem = assertKafkaMessage(
    assertionName = "shouldBePublished",
    typeName = T::class.simpleName ?: "Unknown",
    timeout = atLeastIn,
    expected = "Message matching condition within $atLeastIn"
  ) { onMatch ->
    shouldBePublishedInternal(T::class, atLeastIn) { parsed ->
      parsed.message.isSome { o ->
        val result = condition(ObservedMessage(o, parsed.metadata))
        if (result) onMatch(o)
        result
      }
    }
  }

  @StoveDsl
  suspend inline fun <reified T : Any> shouldBeFailed(
    atLeastIn: Duration = 5.seconds,
    crossinline condition: ObservedMessage<T>.() -> Boolean
  ): KafkaSystem = assertKafkaMessage(
    assertionName = "shouldBeFailed",
    typeName = T::class.simpleName ?: "Unknown",
    timeout = atLeastIn,
    expected = "Failed message within $atLeastIn"
  ) { onMatch ->
    shouldBeFailedInternal(T::class, atLeastIn) { parsed ->
      parsed.message.isSome { o ->
        val result = condition(ObservedMessage(o, parsed.metadata))
        if (result) onMatch(o)
        result
      }
    }
  }

  /**
   * Helper to reduce boilerplate in Kafka assertion methods.
   * Handles try-catch, recording, and re-throwing.
   */
  @PublishedApi
  internal suspend inline fun <T : Any> assertKafkaMessage(
    assertionName: String,
    typeName: String,
    timeout: Duration,
    expected: String,
    crossinline block: suspend ((T) -> Unit) -> Unit
  ): KafkaSystem {
    var matchedMessage: T? = null

    val result = runCatching {
      coroutineScope {
        block { matchedMessage = it }
      }
    }

    val failure = result.exceptionOrNull()?.let { e ->
      e as? AssertionError ?: AssertionError(
        "Expected $assertionName<$typeName> matching condition within $timeout, but none was found",
        e
      )
    }

    if (result.isSuccess) {
      recordSuccess(
        action = "$assertionName<$typeName>",
        output = matchedMessage.toOption(),
        metadata = mapOf("timeout" to timeout.toString())
      )
    } else {
      reporter.record(
        ReportEntry.failure(
          system = reportSystemName,
          testId = reporter.currentTestId(),
          action = "$assertionName<$typeName>",
          error = failure?.message ?: "No matching message found",
          expected = expected.some(),
          actual = (matchedMessage ?: "No matching message found").some()
        )
      )
    }

    failure?.let { throw it }
    return this
  }

  @StoveDsl
  suspend inline fun <reified T : Any> shouldBeRetried(
    atLeastIn: Duration = 5.seconds,
    times: Int = 1,
    crossinline condition: ObservedMessage<T>.() -> Boolean
  ): KafkaSystem = coroutineScope {
    shouldBeRetriedInternal(T::class, atLeastIn, times) { parsed ->
      parsed.message.isSome { o -> condition(ObservedMessage(o, parsed.metadata)) }
    }
  }.let { this }

  /**
   * Waits until the consumed message is seen. This does not mean committed.
   */
  @StoveDsl
  @Suppress("MagicNumber")
  suspend inline fun peekConsumedMessages(
    atLeastIn: Duration = 5.seconds,
    topic: String,
    crossinline condition: (ConsumedRecord) -> Boolean
  ) = withTimeout(atLeastIn) {
    var offset = -1L
    var loop = true
    while (loop) {
      sink.store
        .consumedMessages()
        .filter { it.topic == topic && it.offset > offset }
        .onEach { offset = it.offset }
        .map { ConsumedRecord(it.topic, it.key, it.message.toByteArray(), it.headers, it.offset, it.partition) }
        .forEach { loop = !condition(it) }
      delay(100)
    }
  }

  /**
   * Waits until the committed message is seen with the given condition.
   */
  @StoveDsl
  @Suppress("MagicNumber")
  suspend inline fun peekCommittedMessages(
    atLeastIn: Duration = 5.seconds,
    topic: String,
    crossinline condition: (CommittedRecord) -> Boolean
  ) = withTimeout(atLeastIn) {
    var offset = -1L
    var loop = true
    while (loop) {
      sink.store
        .committedMessages()
        .filter { it.topic == topic && it.offset > offset }
        .onEach { offset = it.offset }
        .map { CommittedRecord(it.topic, it.metadata, it.offset, it.partition) }
        .forEach { loop = !condition(it) }
      delay(100)
    }
  }

  /**
   * Waits until the published message is seen with the given condition.
   */
  @StoveDsl
  @Suppress("MagicNumber")
  suspend inline fun peekPublishedMessages(
    atLeastIn: Duration = 5.seconds,
    topic: String,
    crossinline condition: (PublishedRecord) -> Boolean
  ) = withTimeout(atLeastIn) {
    val seenIds = mutableMapOf<String, PublishedMessage>()
    var loop = true
    while (loop) {
      sink.store
        .publishedMessages()
        .filter { it.topic == topic && !seenIds.containsKey(it.id) }
        .onEach { seenIds[it.id] = it }
        .map { PublishedRecord(it.topic, it.key, it.message.toByteArray(), it.headers) }
        .forEach { loop = !condition(it) }
      delay(100)
    }
  }

  /**
   * Creates an inflight consumer that consumes messages from the given topic.
   */
  @StoveDsl
  suspend fun <K : Any, V : Any> consumer(
    topic: String,
    readOnly: Boolean = true,
    autoOffsetReset: String = "earliest",
    autoCreateTopics: Boolean = false,
    config: (Properties) -> Unit = {},
    keyDeserializer: Deserializer<K> = StoveKafkaValueDeserializer(),
    valueDeserializer: Deserializer<V> = StoveKafkaValueDeserializer(),
    keepConsumingAtLeastFor: Duration = 5.seconds,
    pollTimeout: Duration = (keepConsumingAtLeastFor.inWholeMilliseconds / 2).milliseconds,
    groupId: String = UUID.randomUUID().toString(),
    onConsume: suspend (ConsumerRecord<K, V>) -> Unit
  ) = consume(
    autoOffsetReset,
    readOnly,
    autoCreateTopics,
    config,
    keyDeserializer,
    valueDeserializer,
    topic,
    pollTimeout,
    keepConsumingAtLeastFor,
    groupId,
    onConsume
  )

  /**
   * Pauses the container. Use with care, as it will pause the container which might affect other tests.
   * This operation is not supported when using a provided instance or embedded Kafka.
   */
  @StoveDsl
  fun pause(): KafkaSystem = withContainerOrWarn("pause") { it.pause() }

  /**
   * Unpauses the container. Use with care, as it will unpause the container which might affect other tests.
   * This operation is not supported when using a provided instance or embedded Kafka.
   */
  @StoveDsl
  fun unpause(): KafkaSystem = withContainerOrWarn("unpause") { it.unpause() }

  /**
   * Provides access to the message store of the KafkaSystem.
   */
  @StoveDsl
  fun messageStore(): MessageStore = this.sink.store

  @StoveDsl
  suspend fun adminOperations(block: suspend Admin.() -> Unit) = block(adminClient)

  @PublishedApi
  internal suspend fun <T : Any> shouldBeConsumedInternal(
    clazz: KClass<T>,
    atLeastIn: Duration,
    condition: (message: ParsedMessage<T>) -> Boolean
  ): Unit = coroutineScope { sink.waitUntilConsumed(atLeastIn, clazz, condition) }

  @PublishedApi
  internal suspend fun <T : Any> shouldBeFailedInternal(
    clazz: KClass<T>,
    atLeastIn: Duration,
    condition: (message: ParsedMessage<T>) -> Boolean
  ): Unit = coroutineScope { sink.waitUntilFailed(atLeastIn, clazz, condition) }

  @PublishedApi
  internal suspend fun <T : Any> shouldBePublishedInternal(
    clazz: KClass<T>,
    atLeastIn: Duration,
    condition: (message: ParsedMessage<T>) -> Boolean
  ): Unit = coroutineScope { sink.waitUntilPublished(atLeastIn, clazz, condition) }

  @PublishedApi
  internal suspend fun <T : Any> shouldBeRetriedInternal(
    clazz: KClass<T>,
    atLeastIn: Duration,
    times: Int,
    condition: (message: ParsedMessage<T>) -> Boolean
  ): Unit = coroutineScope { sink.waitUntilRetried(atLeastIn, times, clazz, condition) }

  private suspend fun obtainExposedConfiguration(): KafkaExposedConfiguration =
    when {
      context.options is ProvidedKafkaSystemOptions -> context.options.config
      context.runtime is EmbeddedKafkaRuntime -> startEmbeddedKafka()
      context.runtime is StoveKafkaContainer -> startKafkaContainer(context.runtime)
      else -> throw UnsupportedOperationException("Unsupported runtime type: ${context.runtime::class}")
    }

  private suspend fun startEmbeddedKafka(): KafkaExposedConfiguration = state.capture {
    val config = EmbeddedKafkaConfig.apply(0, 0, `Map$`.`MODULE$`.empty(), `Map$`.`MODULE$`.empty(), `Map$`.`MODULE$`.empty())
    val server = EmbeddedKafka.start(config)
    while (!EmbeddedKafka.isRunning()) {
      delay(100)
    }
    KafkaExposedConfiguration("0.0.0.0:${server.config().kafkaPort()}", StoveKafkaBridge::class.java.name)
  }

  private suspend fun startKafkaContainer(container: StoveKafkaContainer): KafkaExposedConfiguration = state.capture {
    container.start()
    KafkaExposedConfiguration(container.bootstrapServers, StoveKafkaBridge::class.java.name)
  }

  private suspend fun stopEmbeddedKafka() {
    EmbeddedKafka.stop()
    while (EmbeddedKafka.isRunning()) {
      delay(100)
    }
  }

  @OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class)
  @Suppress("MagicNumber")
  private suspend fun <K : Any, V : Any> consume(
    autoOffsetReset: String,
    readOnly: Boolean,
    autoCreateTopics: Boolean,
    config: (Properties) -> Unit,
    keyDeserializer: Deserializer<K>,
    valueDeserializer: Deserializer<V>,
    topic: String,
    pollTimeout: Duration,
    keepConsumingAtLeastFor: Duration,
    groupId: String,
    onConsume: suspend (ConsumerRecord<K, V>) -> Unit
  ) = coroutineScope {
    val props = createConsumerProperties(autoOffsetReset, autoCreateTopics, groupId).apply(config)
    val c = KafkaConsumer(props, keyDeserializer, valueDeserializer)
    c.subscribe(listOf(topic))
    val channel = Channel<ConsumerRecord<K, V>>()
    val job = launch {
      while (isActive) {
        c.poll(pollTimeout.toJavaDuration()).forEach { channel.send(it) }
        delay(100)
      }
    }
    whileSelect {
      onTimeout(keepConsumingAtLeastFor) {
        c.close()
        job.cancelAndJoin()
        false
      }
      channel.onReceive {
        onConsume(it)
        if (!readOnly) c.commitSync()
        !channel.isClosedForReceive
      }
    }
  }

  private fun createConsumerProperties(
    autoOffsetReset: String,
    autoCreateTopics: Boolean,
    groupId: String
  ): Properties = Properties().apply {
    this[ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG] = exposedConfiguration.bootstrapServers
    this[ConsumerConfig.GROUP_ID_CONFIG] = groupId
    this[ConsumerConfig.AUTO_OFFSET_RESET_CONFIG] = autoOffsetReset
    this[ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG] = false
    this[ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG] = autoCreateTopics
    this[ConsumerConfig.INTERCEPTOR_CLASSES_CONFIG] = exposedConfiguration.interceptorClass
  }

  private fun createPublisher(config: KafkaExposedConfiguration): KafkaProducer<String, Any> = KafkaProducer(
    buildMap {
      put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, config.bootstrapServers)
      put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer::class.java.name)
      put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, context.options.valueSerializer::class.java.name)
      put(ProducerConfig.CLIENT_ID_CONFIG, "stove-kafka-producer")
      put(ProducerConfig.ACKS_CONFIG, "1")
      if (context.options.listenPublishedMessagesFromStove) {
        put(ProducerConfig.INTERCEPTOR_CLASSES_CONFIG, config.interceptorClass)
      }
    }
  )

  private fun createAdminClient(config: KafkaExposedConfiguration): Admin = Admin.create(
    mapOf<String, Any>(
      AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG to config.bootstrapServers,
      AdminClientConfig.CLIENT_ID_CONFIG to "stove-kafka-admin-client"
    ).toProperties()
  )

  private suspend fun startGrpcServer(): Server {
    System.setProperty(STOVE_KAFKA_BRIDGE_PORT, context.options.bridgeGrpcServerPort.toString())
    return Try {
      NettyServerBuilder
        .forAddress(InetSocketAddress(InetAddress.getLoopbackAddress(), context.options.bridgeGrpcServerPort))
        .executor(StoveKafkaCoroutineScope.also { it.ensureActive() }.asExecutor)
        .addService(StoveKafkaObserverGrpcServer(sink))
        .handshakeTimeout(GRPC_TIMEOUT_IN_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
        .permitKeepAliveTime(GRPC_TIMEOUT_IN_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
        .keepAliveTime(GRPC_TIMEOUT_IN_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
        .keepAliveTimeout(GRPC_TIMEOUT_IN_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
        .maxConnectionAge(GRPC_TIMEOUT_IN_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
        .maxConnectionAgeGrace(GRPC_TIMEOUT_IN_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
        .maxConnectionIdle(GRPC_TIMEOUT_IN_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
        .maxInboundMessageSize(MAX_MESSAGE_SIZE)
        .maxInboundMetadataSize(MAX_MESSAGE_SIZE)
        .permitKeepAliveWithoutCalls(true)
        .build()
        .start()
        .also { waitUntilHealthy(it, 30.seconds) }
    }.recover {
      logger.error("Failed to start Stove Message Sink Grpc Server", it)
      throw it
    }.map {
      logger.info("Stove Sink Grpc Server started on port ${context.options.bridgeGrpcServerPort}")
      it
    }.get()
  }

  private suspend fun waitUntilHealthy(server: Server, duration: Duration) {
    val client = GrpcUtils.createClient(server.port.toString(), StoveKafkaCoroutineScope)
    var healthy = false
    withTimeout(duration) {
      while (!healthy) {
        logger.info("Waiting for Stove Message Sink Grpc Server to be healthy")
        Try {
          val response = client.healthCheck().execute(HealthCheckRequest())
          healthy = response.status == HealthCheckResponse.ServingStatus.SERVING
        }
        delay(GRPC_SERVER_DELAY)
      }
      logger.info("Stove Message Sink Grpc Server is healthy!")
    }
  }

  private inline fun withContainerOrWarn(
    operation: String,
    action: (StoveKafkaContainer) -> Unit
  ): KafkaSystem = when (val runtime = context.runtime) {
    is ProvidedRuntime, is EmbeddedKafkaRuntime -> {
      logger.warn("$operation() is not supported when using embedded Kafka or a provided instance")
      this
    }

    is StoveKafkaContainer -> {
      action(runtime)
      this
    }

    else -> {
      throw UnsupportedOperationException("Unsupported runtime type: ${runtime::class}")
    }
  }

  companion object {
    private const val GRPC_SERVER_DELAY = 500L
    private const val GRPC_TIMEOUT_IN_SECONDS = 300L
    private const val MAX_MESSAGE_SIZE = 1024 * 1024 * 1024
  }
}
