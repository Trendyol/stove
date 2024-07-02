package com.trendyol.stove.testing.e2e.standalone.kafka

import arrow.core.*
import com.fasterxml.jackson.databind.ObjectMapper
import com.trendyol.stove.functional.*
import com.trendyol.stove.testing.e2e.containers.RandomPortFinder
import com.trendyol.stove.testing.e2e.messaging.*
import com.trendyol.stove.testing.e2e.serialization.StoveObjectMapper
import com.trendyol.stove.testing.e2e.standalone.kafka.intercepting.*
import com.trendyol.stove.testing.e2e.system.TestSystem
import com.trendyol.stove.testing.e2e.system.abstractions.*
import com.trendyol.stove.testing.e2e.system.annotations.StoveDsl
import io.github.embeddedkafka.*
import io.grpc.*
import kotlinx.coroutines.*
import org.apache.kafka.clients.admin.*
import org.apache.kafka.clients.producer.*
import org.apache.kafka.common.serialization.StringSerializer
import org.slf4j.*
import scala.collection.immutable.HashMap
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.reflect.KClass
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

var stoveKafkaObjectMapperRef: ObjectMapper = StoveObjectMapper.Default
var stoveKafkaBridgePortDefault = "50051"
const val STOVE_KAFKA_BRIDGE_PORT = "STOVE_KAFKA_BRIDGE_PORT"
internal val StoveKafkaCoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

@Suppress("TooManyFunctions")
@StoveDsl
class KafkaSystem(
  override val testSystem: TestSystem,
  private val context: KafkaContext
) : PluggedSystem, ExposesConfiguration, RunAware, AfterRunAware {
  private lateinit var exposedConfiguration: KafkaExposedConfiguration
  private lateinit var adminClient: Admin
  private lateinit var kafkaPublisher: KafkaProducer<String, Any>
  private lateinit var grpcServer: Server

  @PublishedApi
  internal lateinit var sink: TestSystemMessageSink
  private val logger: Logger = LoggerFactory.getLogger(javaClass)
  private val state: StateStorage<KafkaExposedConfiguration> =
    testSystem.options.createStateStorage<KafkaExposedConfiguration, KafkaSystem>()
  private lateinit var kafka: EmbeddedK

  override suspend fun run() {
    exposedConfiguration = state.capture {
      when {
        context.options.useEmbeddedKafka -> {
          val config = EmbeddedKafkaConfig.apply(
            RandomPortFinder.findRandomOpenPort(),
            RandomPortFinder.findRandomOpenPort(),
            HashMap(),
            HashMap(),
            HashMap()
          )
          kafka = EmbeddedKafka.start(config)
          withTimeout(30.seconds) {
            while (!EmbeddedKafka.isRunning()) {
              logger.info("Waiting for Embedded Kafka to be ready")
              delay(500.milliseconds)
            }
          }
          KafkaExposedConfiguration(
            "localhost:${config.kafkaPort()}",
            StoveKafkaBridge::class.java.name
          )
        }

        else -> {
          context.container.start()
          KafkaExposedConfiguration(
            context.container.bootstrapServers,
            StoveKafkaBridge::class.java.name
          )
        }
      }
    }
    adminClient = createAdminClient(exposedConfiguration)
    kafkaPublisher = createPublisher(exposedConfiguration, context.options.listenPublishedMessagesFromStove)
    sink = TestSystemMessageSink(
      adminClient,
      context.options.objectMapper,
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
    listenKafkaSystemPublishedMessages: Boolean
  ): KafkaProducer<String, Any> = KafkaProducer(
    mapOf(
      ProducerConfig.BOOTSTRAP_SERVERS_CONFIG to exposedConfiguration.bootstrapServers,
      ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG to StringSerializer::class.java.name,
      ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG to StoveKafkaValueSerializer::class.java.name,
      ProducerConfig.CLIENT_ID_CONFIG to "stove-kafka-producer",
      ProducerConfig.ACKS_CONFIG to "1"
    ) + (
      if (listenKafkaSystemPublishedMessages) {
        mapOf(
          ProducerConfig.INTERCEPTOR_CLASSES_CONFIG to exposedConfiguration.interceptorClass
        )
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
      ServerBuilder.forPort(context.options.bridgeGrpcServerPort)
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
      kafka.stop(true)
    } else {
      context.container.stop()
    }
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
