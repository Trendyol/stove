@file:Suppress("UNUSED_PARAMETER")

package com.trendyol.stove.testing.e2e.standalone.kafka

import arrow.core.*
import com.fasterxml.jackson.databind.ObjectMapper
import com.trendyol.stove.functional.*
import com.trendyol.stove.testing.e2e.messaging.*
import com.trendyol.stove.testing.e2e.serialization.StoveObjectMapper
import com.trendyol.stove.testing.e2e.standalone.kafka.intercepting.*
import com.trendyol.stove.testing.e2e.system.TestSystem
import com.trendyol.stove.testing.e2e.system.abstractions.*
import com.trendyol.stove.testing.e2e.system.annotations.StoveDsl
import io.github.nomisRev.kafka.*
import io.github.nomisRev.kafka.publisher.*
import io.grpc.ServerBuilder
import kotlinx.coroutines.*
import org.apache.kafka.clients.admin.*
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.StringSerializer
import org.slf4j.*
import kotlin.reflect.KClass
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

var stoveKafkaObjectMapperRef: ObjectMapper = StoveObjectMapper.Default
var stoveKafkaBridgePortDefault = "50051"
const val STOVE_KAFKA_BRIDGE_PORT = "STOVE_KAFKA_BRIDGE_PORT"

@StoveDsl
class KafkaSystem(
  override val testSystem: TestSystem,
  private val context: KafkaContext
) : PluggedSystem, ExposesConfiguration, RunAware, AfterRunAware {
  private lateinit var exposedConfiguration: KafkaExposedConfiguration
  private lateinit var adminClient: Admin
  private lateinit var kafkaPublisher: KafkaPublisher<String, Any>

  @PublishedApi
  internal lateinit var sink: TestSystemMessageSink
  private val logger: Logger = LoggerFactory.getLogger(javaClass)
  private val state: StateOfSystem<KafkaSystem, KafkaExposedConfiguration> = StateOfSystem(
    testSystem.options,
    KafkaSystem::class,
    KafkaExposedConfiguration::class
  )

  override suspend fun run() {
    exposedConfiguration = state.capture {
      context.container.start()
      KafkaExposedConfiguration(context.container.bootstrapServers)
    }
    adminClient = createAdminClient(exposedConfiguration)
    kafkaPublisher = createPublisher(exposedConfiguration)
    sink = TestSystemMessageSink(
      adminClient,
      context.options.objectMapper,
      context.options.topicSuffixes
    )
    startGrpcServer()
  }

  private fun startGrpcServer() {
    System.setProperty(STOVE_KAFKA_BRIDGE_PORT, context.options.bridgeGrpcServerPort.toString())
    Try {
      ServerBuilder.forPort(context.options.bridgeGrpcServerPort)
        .addService(StoveKafkaObserverGrpcServer(sink))
        .build()
        .start()
    }.recover {
      logger.error("Failed to start Wire-Grpc-Server", it)
      throw it
    }.map {
      logger.info("Wire-Grpc-Server started on port ${context.options.bridgeGrpcServerPort}")
    }
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
    kafkaPublisher.publishScope { offer(record) }
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

  private fun createPublisher(exposedConfiguration: KafkaExposedConfiguration): KafkaPublisher<String, Any> = PublisherSettings(
    exposedConfiguration.bootstrapServers,
    StringSerializer(),
    StoveKafkaValueSerializer()
  ).let { KafkaPublisher(it) }

  private fun createAdminClient(exposedConfiguration: KafkaExposedConfiguration): Admin =
    mapOf<String, Any>(
      AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG to exposedConfiguration.bootstrapServers,
      AdminClientConfig.CLIENT_ID_CONFIG to "stove-kafka-admin-client"
    ).let { Admin(AdminSettings(exposedConfiguration.bootstrapServers, it.toProperties())) }

  override fun configuration(): List<String> = context.options.configureExposedConfiguration(exposedConfiguration)

  override suspend fun stop(): Unit = context.container.stop()

  override fun close(): Unit = runBlocking {
    Try {
      kafkaPublisher.close()
      executeWithReuseCheck { stop() }
    }
  }.recover { logger.warn("got an error while stopping: ${it.message}") }.let { }
}
