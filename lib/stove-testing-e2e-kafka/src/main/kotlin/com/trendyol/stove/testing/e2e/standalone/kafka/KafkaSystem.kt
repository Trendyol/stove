package com.trendyol.stove.testing.e2e.standalone.kafka

import arrow.core.*
import com.trendyol.stove.functional.*
import com.trendyol.stove.testing.e2e.messaging.*
import com.trendyol.stove.testing.e2e.serialization.StoveSerde
import com.trendyol.stove.testing.e2e.standalone.kafka.intercepting.*
import com.trendyol.stove.testing.e2e.system.TestSystem
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
var stoveKafkaBridgePortDefault = "50051"
const val STOVE_KAFKA_BRIDGE_PORT = "STOVE_KAFKA_BRIDGE_PORT"
internal val StoveKafkaCoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

@Suppress("TooManyFunctions", "unused")
@StoveDsl
class KafkaSystem(
  override val testSystem: TestSystem,
  private val context: KafkaContext
) : PluggedSystem,
  ExposesConfiguration,
  RunAware,
  AfterRunAware,
  BeforeRunAware {
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
    exposedConfiguration = state.capture {
      if (context.options.useEmbeddedKafka) {
        val config: EmbeddedKafkaConfig = EmbeddedKafkaConfig.apply(
          0,
          0,
          `Map$`.`MODULE$`.empty(),
          `Map$`.`MODULE$`.empty(),
          `Map$`.`MODULE$`.empty()
        )

        val server = EmbeddedKafka.start(
          config
        )

        while (!EmbeddedKafka.isRunning()) {
          delay(100)
        }
        KafkaExposedConfiguration(
          "0.0.0.0:${server.config().kafkaPort()}",
          StoveKafkaBridge::class.java.name
        )
      } else {
        context.container.start()
        KafkaExposedConfiguration(
          context.container.bootstrapServers,
          StoveKafkaBridge::class.java.name
        )
      }
    }
    adminClient = createAdminClient(exposedConfiguration)
    kafkaPublisher = createPublisher(
      exposedConfiguration,
      context.options.listenPublishedMessagesFromStove,
      context.options.valueSerializer
    )
    sink = TestSystemMessageSink(
      adminClient,
      context.options.serde,
      context.options.topicSuffixes
    )
    grpcServer = startGrpcServer()
  }

  override suspend fun afterRun() = Unit

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
    return this
  }

  @StoveDsl
  suspend inline fun <reified T : Any> shouldBeConsumed(
    atLeastIn: Duration = 5.seconds,
    crossinline condition: ObservedMessage<T>.() -> Boolean
  ): KafkaSystem = shouldBeConsumedInternal(T::class, atLeastIn) { parsed ->
    parsed.message.isSome { o -> condition(ObservedMessage(o, parsed.metadata)) }
  }.let { this }

  @StoveDsl
  suspend inline fun <reified T : Any> shouldBePublished(
    atLeastIn: Duration = 5.seconds,
    crossinline condition: ObservedMessage<T>.() -> Boolean
  ): KafkaSystem = coroutineScope {
    shouldBePublishedInternal(T::class, atLeastIn) { parsed ->
      parsed.message.isSome { o -> condition(ObservedMessage(o, parsed.metadata)) }
    }
  }.let { this }

  @StoveDsl
  suspend inline fun <reified T : Any> shouldBeFailed(
    atLeastIn: Duration = 5.seconds,
    crossinline condition: ObservedMessage<T>.() -> Boolean
  ): KafkaSystem = coroutineScope {
    shouldBeFailedInternal(T::class, atLeastIn) { parsed ->
      parsed.message.isSome { o -> condition(ObservedMessage(o, parsed.metadata)) }
    }
  }.let { this }

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
   * @param atLeastIn Duration
   * @param topic String
   * @param condition Function1<ConsumedMessage, Boolean>
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
        .forEach {
          loop = !condition(it)
        }
      delay(100)
    }
  }

  /**
   * Waits until the committed message is seen with the given condition.
   * @param atLeastIn Duration
   * @param topic String
   * @param condition Function1<ConsumedMessage, Boolean>
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
        .forEach {
          loop = !condition(it)
        }
      delay(100)
    }
  }

  /**
   * Waits until the committed message is seen with the given condition.
   * @param atLeastIn Duration
   * @param topic String
   * @param condition Function1<ConsumedMessage, Boolean>
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
        .forEach {
          loop = !condition(it)
        }
      delay(100)
    }
  }

  /**
   * Creates an inflight consumer that consumes messages from the given topic.
   * @param topic String
   * @param readOnly Boolean If true, the consumer will not commit the messages.
   * @param autoOffsetReset String The offset reset strategy. Default is "earliest".
   * @param autoCreateTopics Boolean If true, the consumer will create the topic if it does not exist.
   * @param config Function1<Properties, Unit> Additional configuration for the consumer.
   * @param keyDeserializer Deserializer<K> The key deserializer. Default is StoveKafkaValueDeserializer.
   * @param valueDeserializer Deserializer<V> The value deserializer. Default is StoveKafkaValueDeserializer.
   * @param keepConsumingAtLeastFor Duration The duration to keep consuming messages.
   * @param pollTimeout Duration The poll timeout for the consumer.
   * @param onConsume Function1<ConsumerRecord<K, V>, Unit> The function to be executed when a message is consumed.
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
    val props = Properties()
      .apply {
        this[ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG] = exposedConfiguration.bootstrapServers
        this[ConsumerConfig.GROUP_ID_CONFIG] = groupId
        this[ConsumerConfig.AUTO_OFFSET_RESET_CONFIG] = autoOffsetReset
        this[ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG] = false
        this[ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG] = autoCreateTopics
        this[ConsumerConfig.INTERCEPTOR_CLASSES_CONFIG] = exposedConfiguration.interceptorClass
      }.apply(config)
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

  /**
   * Pauses the container. Use with care, as it will pause the container which might affect other tests.
   * @return KafkaSystem
   */
  @StoveDsl
  fun pause(): KafkaSystem {
    if (context.options.useEmbeddedKafka) {
      return this
    }
    return context.container.pause().let { this }
  }

  /**
   * Unpauses the container. Use with care, as it will unpause the container which might affect other tests.
   * @return KafkaSystem
   */
  @StoveDsl
  fun unpause(): KafkaSystem {
    if (context.options.useEmbeddedKafka) {
      return this
    }
    return context.container.unpause().let { this }
  }

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

  private fun createPublisher(
    exposedConfiguration: KafkaExposedConfiguration,
    listenKafkaSystemPublishedMessages: Boolean,
    kafkaValueSerializerClass: Serializer<Any>
  ): KafkaProducer<String, Any> = KafkaProducer(
    mapOf(
      ProducerConfig.BOOTSTRAP_SERVERS_CONFIG to exposedConfiguration.bootstrapServers,
      ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG to StringSerializer::class.java.name,
      ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG to kafkaValueSerializerClass::class.java.name,
      ProducerConfig.CLIENT_ID_CONFIG to "stove-kafka-producer",
      ProducerConfig.ACKS_CONFIG to "1"
    ) + (
      if (listenKafkaSystemPublishedMessages) {
        mapOf(ProducerConfig.INTERCEPTOR_CLASSES_CONFIG to exposedConfiguration.interceptorClass)
      } else {
        emptyMap()
      }
    )
  )

  private fun createAdminClient(
    exposedConfiguration: KafkaExposedConfiguration
  ): Admin = mapOf<String, Any>(
    AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG to exposedConfiguration.bootstrapServers,
    AdminClientConfig.CLIENT_ID_CONFIG to "stove-kafka-admin-client"
  ).let { Admin.create(it.toProperties()) }

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

  companion object {
    private const val GRPC_SERVER_DELAY = 500L
    private const val GRPC_TIMEOUT_IN_SECONDS = 300L
    private const val MAX_MESSAGE_SIZE = 1024 * 1024 * 1024
  }

  override fun configuration(): List<String> = context.options.configureExposedConfiguration(exposedConfiguration)

  override suspend fun stop() {
    if (context.options.useEmbeddedKafka) {
      EmbeddedKafka.stop()
      while (EmbeddedKafka.isRunning()) {
        delay(100)
      }
      return
    }
    context.container.stop()
  }

  override fun close(): Unit = runBlocking {
    Try {
      grpcServer.shutdownNow()
      StoveKafkaCoroutineScope.cancel()
      kafkaPublisher.close()
      executeWithReuseCheck { stop() }
    }
  }.recover { logger.warn("got an error while stopping: ${it.message}") }.let { }
}
