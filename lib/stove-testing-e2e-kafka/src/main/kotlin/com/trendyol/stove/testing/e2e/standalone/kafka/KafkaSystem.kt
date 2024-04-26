@file:Suppress("UNCHECKED_CAST", "UNUSED_PARAMETER")

package com.trendyol.stove.testing.e2e.standalone.kafka

import arrow.core.*
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.trendyol.stove.functional.*
import com.trendyol.stove.testing.e2e.serialization.StoveObjectMapper
import com.trendyol.stove.testing.e2e.standalone.kafka.intercepting.*
import com.trendyol.stove.testing.e2e.system.TestSystem
import com.trendyol.stove.testing.e2e.system.abstractions.*
import com.trendyol.stove.testing.e2e.system.annotations.StoveDsl
import io.github.nomisRev.kafka.*
import io.github.nomisRev.kafka.publisher.*
import io.grpc.ServerBuilder
import kotlinx.coroutines.runBlocking
import org.apache.kafka.clients.admin.*
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.*
import org.slf4j.*
import kotlin.reflect.KClass
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

var stoveKafkaObjectMapperRef: ObjectMapper = StoveObjectMapper.Default
const val STOVE_KAFKA_BRIDGE_PORT = "STOVE_KAFKA_BRIDGE_PORT"
const val STOVE_KAFKA_BRIDGE_PORT_DEFAULT = "50051"

@StoveDsl
class KafkaSystem(
  override val testSystem: TestSystem,
  private val context: KafkaContext
) : PluggedSystem, ExposesConfiguration, RunAware, AfterRunAware {
  private lateinit var exposedConfiguration: KafkaExposedConfiguration
  private lateinit var adminClient: Admin
  private lateinit var kafkapublisher: KafkaPublisher<String, Any>
  private lateinit var sink: TestSystemMessageSink
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
    kafkapublisher = createPublisher(exposedConfiguration)
    sink = TestSystemMessageSink(
      adminClient,
      context.options.objectMapper,
      InterceptionOptions(context.options.errorTopicSuffixes)
    )
    startGrpcServer()
  }

  private fun startGrpcServer() {
    System.setProperty(STOVE_KAFKA_BRIDGE_PORT, context.options.bridgeGrpcServerPort.toString())
    Try {
      ServerBuilder.forPort(context.options.bridgeGrpcServerPort)
        .addService(StoveKafkaObserverGrpcServerAdapter(sink))
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
    testCase: Option<String> = None
  ): KafkaSystem {
    val record = ProducerRecord<String, Any>(topic, message)
    testCase.map { record.headers().add("testCase", it.toByteArray()) }
    kafkapublisher.publishScope { offer(record) }
    return this
  }

  @StoveDsl
  suspend fun shouldBeConsumed(
    atLeastIn: Duration = 5.seconds,
    message: Any
  ): KafkaSystem = sink
    .waitUntilConsumed(atLeastIn, message::class) { actual -> actual.isSome { it == message } }
    .let { this }

  suspend fun shouldBeFailed(
    atLeastIn: Duration = 5.seconds,
    message: Any,
    exception: Throwable
  ): KafkaSystem {
    TODO("Not yet implemented")
  }

  @PublishedApi
  internal suspend fun <T : Any> shouldBeFailed(
    atLeastIn: Duration = 5.seconds,
    condition: (T, Throwable) -> Boolean,
    clazz: KClass<T>
  ): KafkaSystem {
    TODO("Not yet implemented")
  }

  @PublishedApi
  internal suspend fun <T : Any> shouldBeConsumed(
    atLeastIn: Duration = 5.seconds,
    condition: (T) -> Boolean,
    clazz: KClass<T>
  ): KafkaSystem = sink
    .waitUntilConsumed(atLeastIn, clazz) { actual -> actual.isSome { condition(it) } }
    .let { this }

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

  override fun configuration(): List<String> = context.options.configureExposedConfiguration(exposedConfiguration) +
    listOf(
      "kafka.bootstrapServers=${exposedConfiguration.bootstrapServers}",
      "kafka.isSecure=false"
    )

  override suspend fun stop(): Unit = context.container.stop()

  override fun close(): Unit =
    runBlocking {
      Try {
        kafkapublisher.close()
        executeWithReuseCheck { stop() }
      }
    }.recover { logger.warn("got an error while stopping: ${it.message}") }.let { }
}

class StoveKafkaValueDeserializer<T : Any> : Deserializer<T> {
  private val objectMapper = StoveObjectMapper.Default

  @Suppress("UNCHECKED_CAST")
  override fun deserialize(
    topic: String,
    data: ByteArray
  ): T = objectMapper.readValue<Any>(data) as T
}

class StoveKafkaValueSerializer<T : Any> : Serializer<T> {
  private val objectMapper = StoveObjectMapper.Default

  override fun serialize(
    topic: String,
    data: T
  ): ByteArray = objectMapper.writeValueAsBytes(data)
}
