@file:Suppress("UNCHECKED_CAST", "UNUSED_PARAMETER")

package com.trendyol.stove.testing.e2e.standalone.kafka

import arrow.core.None
import arrow.core.Option
import arrow.core.getOrElse
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.trendyol.stove.functional.Try
import com.trendyol.stove.functional.recover
import com.trendyol.stove.testing.e2e.containers.DEFAULT_REGISTRY
import com.trendyol.stove.testing.e2e.containers.withProvidedRegistry
import com.trendyol.stove.testing.e2e.serialization.StoveObjectMapper
import com.trendyol.stove.testing.e2e.standalone.kafka.intercepting.InterceptionOptions
import com.trendyol.stove.testing.e2e.standalone.kafka.intercepting.TestSystemKafkaInterceptor
import com.trendyol.stove.testing.e2e.system.TestSystem
import com.trendyol.stove.testing.e2e.system.ValidationDsl
import com.trendyol.stove.testing.e2e.system.WithDsl
import com.trendyol.stove.testing.e2e.system.abstractions.*
import io.github.nomisRev.kafka.Admin
import io.github.nomisRev.kafka.AdminSettings
import io.github.nomisRev.kafka.receiver.KafkaReceiver
import io.github.nomisRev.kafka.receiver.ReceiverSettings
import io.github.nomisRev.kafka.sendAwait
import kotlinx.coroutines.runBlocking
import org.apache.kafka.clients.admin.Admin
import org.apache.kafka.clients.admin.AdminClientConfig
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.Deserializer
import org.apache.kafka.common.serialization.Serializer
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.testcontainers.containers.KafkaContainer
import kotlin.reflect.KClass
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

data class KafkaExposedConfiguration(
    val bootstrapServers: String
) : ExposedConfiguration

data class KafkaSystemOptions(
    val registry: String = DEFAULT_REGISTRY,
    val ports: List<Int> = listOf(9092, 9093),
    val errorTopicSuffixes: List<String> = listOf("error", "errorTopic", "retry", "retryTopic"),
    val objectMapper: ObjectMapper = StoveObjectMapper.Default,
    val containerOptionsConfigurer: KafkaContainer.() -> Unit = { },
    override val configureExposedConfiguration: (KafkaExposedConfiguration) -> List<String> = { _ -> listOf() }
) : SystemOptions, ConfiguresExposedConfiguration<KafkaExposedConfiguration>

data class KafkaContext(
    val container: KafkaContainer,
    val options: KafkaSystemOptions
)

internal fun TestSystem.kafka(): KafkaSystem = getOrNone<KafkaSystem>().getOrElse { throw SystemNotRegisteredException(KafkaSystem::class) }

internal fun TestSystem.withKafka(options: KafkaSystemOptions = KafkaSystemOptions()): TestSystem {
    val kafka =
        withProvidedRegistry("confluentinc/cp-kafka:latest", options.registry) {
            KafkaContainer(it).withExposedPorts(*options.ports.toTypedArray())
                .withReuse(this.options.keepDependenciesRunning)
                .apply {
                    options.containerOptionsConfigurer(this)
                }
        }
    getOrRegister(KafkaSystem(this, KafkaContext(kafka, options)))
    return this
}

suspend fun ValidationDsl.kafka(validation: suspend KafkaSystem.() -> Unit): Unit = validation(this.testSystem.kafka())

fun WithDsl.kafka(configure: () -> KafkaSystemOptions): TestSystem = this.testSystem.withKafka(configure())

class KafkaSystem(
    override val testSystem: TestSystem,
    private val context: KafkaContext
) : PluggedSystem, ExposesConfiguration, RunAware, AfterRunAware {
    private lateinit var exposedConfiguration: KafkaExposedConfiguration
    private lateinit var adminClient: Admin
    private lateinit var kafkaProducer: KafkaProducer<String, Any>
    private lateinit var subscribeToAllConsumer: SubscribeToAll
    private lateinit var interceptor: TestSystemKafkaInterceptor
    private val assertedMessages: MutableList<Any> = mutableListOf()
    private val assertedConditions: MutableList<(Any) -> Boolean> = mutableListOf()
    private val logger: Logger = LoggerFactory.getLogger(javaClass)
    private val state: StateOfSystem<KafkaSystem, KafkaExposedConfiguration> =
        StateOfSystem(
            testSystem.options,
            KafkaSystem::class,
            KafkaExposedConfiguration::class
        )

    suspend fun publish(
        topic: String,
        message: Any,
        key: Option<String> = None,
        headers: Map<String, String> = mapOf(),
        testCase: Option<String> = None
    ): KafkaSystem {
        val record = ProducerRecord<String, Any>(topic, message)
        testCase.map { record.headers().add("testCase", it.toByteArray()) }
        kafkaProducer.sendAwait(record)
        return this
    }

    suspend fun shouldBeConsumed(
        atLeastIn: Duration = 5.seconds,
        message: Any
    ): KafkaSystem =
        interceptor
            .also { assertedMessages.add(message) }
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
    internal suspend fun <T : Any> shouldBeConsumedOnCondition(
        atLeastIn: Duration = 5.seconds,
        condition: (T) -> Boolean,
        clazz: KClass<T>
    ): KafkaSystem =
        interceptor
            .also { assertedConditions.add(condition as (Any) -> Boolean) }
            .waitUntilConsumed(atLeastIn, clazz) { actual -> actual.isSome { condition(it) } }
            .let { this }

    @PublishedApi
    internal suspend fun <T : Any> shouldBeFailedOnCondition(
        atLeastIn: Duration = 5.seconds,
        condition: (T, Throwable) -> Boolean,
        clazz: KClass<T>
    ): KafkaSystem {
        TODO("Not yet implemented")
    }

    override suspend fun run() {
        exposedConfiguration =
            state.capture {
                context.container.start()
                KafkaExposedConfiguration(context.container.bootstrapServers)
            }
        adminClient = createAdminClient(exposedConfiguration)
        kafkaProducer = createProducer(exposedConfiguration)
    }

    override suspend fun afterRun() {
        interceptor =
            TestSystemKafkaInterceptor(
                adminClient,
                context.options.objectMapper,
                InterceptionOptions(errorTopicSuffixes = context.options.errorTopicSuffixes)
            )
        subscribeToAllConsumer =
            SubscribeToAll(
                adminClient,
                consumer(exposedConfiguration),
                interceptor
            )
        subscribeToAllConsumer.start()
    }

    private fun consumer(cfg: KafkaExposedConfiguration): KafkaReceiver<String, Any> =
        mapOf(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to cfg.bootstrapServers,
            ConsumerConfig.GROUP_ID_CONFIG to "stove-kafka-subscribe-to-all",
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "earliest",
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java,
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG to StoveKafkaValueDeserializer::class.java,
            ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG to false,
            ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG to 2.milliseconds.inWholeMilliseconds.toInt()
        ).let {
            KafkaReceiver(
                ReceiverSettings(
                    cfg.bootstrapServers,
                    StringDeserializer(),
                    StoveKafkaValueDeserializer(),
                    SUBSCRIBE_TO_ALL_GROUP_ID,
                    properties = it.toProperties()
                )
            )
        }

    private fun createProducer(exposedConfiguration: KafkaExposedConfiguration): KafkaProducer<String, Any> =
        mapOf(
            ProducerConfig.BOOTSTRAP_SERVERS_CONFIG to exposedConfiguration.bootstrapServers,
            ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG to StringSerializer::class.java,
            ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG to StoveKafkaValueSerializer::class.java
        ).let { KafkaProducer(it) }

    private fun createAdminClient(exposedConfiguration: KafkaExposedConfiguration): Admin =
        mapOf<String, Any>(
            AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG to exposedConfiguration.bootstrapServers,
            AdminClientConfig.CLIENT_ID_CONFIG to "stove-kafka-admin-client"
        ).let { Admin(AdminSettings(exposedConfiguration.bootstrapServers, it.toProperties())) }

    override fun configuration(): List<String> =
        context.options.configureExposedConfiguration(exposedConfiguration) +
            listOf(
                "kafka.bootstrapServers=${exposedConfiguration.bootstrapServers}",
                "kafka.isSecure=false"
            )

    override suspend fun stop(): Unit = context.container.stop()

    override fun close(): Unit =
        runBlocking {
            Try {
                subscribeToAllConsumer.close()
                kafkaProducer.close()
                executeWithReuseCheck { stop() }
            }
        }.recover { logger.warn("got an error while stopping: ${it.message}") }.let { }

    companion object {
        const val SUBSCRIBE_TO_ALL_GROUP_ID = "stove-kafka-subscribe-to-all"
    }
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
