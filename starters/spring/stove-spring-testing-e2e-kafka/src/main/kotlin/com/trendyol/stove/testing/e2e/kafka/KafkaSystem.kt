package com.trendyol.stove.testing.e2e.kafka

import arrow.core.Option
import arrow.core.Some
import arrow.core.getOrElse
import com.fasterxml.jackson.databind.ObjectMapper
import com.trendyol.stove.functional.Try
import com.trendyol.stove.functional.recover
import com.trendyol.stove.testing.e2e.containers.DEFAULT_REGISTRY
import com.trendyol.stove.testing.e2e.containers.withProvidedRegistry
import com.trendyol.stove.testing.e2e.messaging.AssertsPublishing
import com.trendyol.stove.testing.e2e.messaging.MessagingSystem
import com.trendyol.stove.testing.e2e.serialization.StoveObjectMapper
import com.trendyol.stove.testing.e2e.system.TestSystem
import com.trendyol.stove.testing.e2e.system.abstractions.*
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.header.internals.RecordHeader
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.getBean
import org.springframework.context.ApplicationContext
import org.springframework.kafka.core.KafkaTemplate
import org.testcontainers.containers.KafkaContainer
import kotlin.reflect.KClass
import kotlin.time.Duration

data class KafkaExposedConfiguration(
    val bootstrapServers: String,
) : ExposedConfiguration

data class KafkaSystemOptions(
    val registry: String = DEFAULT_REGISTRY,
    val ports: List<Int> = listOf(9092, 9093),
    val objectMapper: ObjectMapper = StoveObjectMapper.Default,
    override val configureExposedConfiguration: (KafkaExposedConfiguration) -> List<String> = { _ -> listOf() },
) : SystemOptions, ConfiguresExposedConfiguration<KafkaExposedConfiguration>

data class KafkaContext(
    val container: KafkaContainer,
    val objectMapper: ObjectMapper,
    val configureExposedConfiguration: (KafkaExposedConfiguration) -> List<String>,
)

fun TestSystem.withKafka(
    options: KafkaSystemOptions = KafkaSystemOptions(),
): TestSystem = withProvidedRegistry("confluentinc/cp-kafka:latest", options.registry) {
    KafkaContainer(it).withExposedPorts(*options.ports.toTypedArray()).withEmbeddedZookeeper().withReuse(
        this.options.keepDependenciesRunning
    )
}.let { getOrRegister(KafkaSystem(this, KafkaContext(it, options.objectMapper, options.configureExposedConfiguration))) }
    .let { this }

fun TestSystem.kafka(): KafkaSystem =
    getOrNone<KafkaSystem>().getOrElse { throw SystemNotRegisteredException(KafkaSystem::class) }

class KafkaSystem(
    override val testSystem: TestSystem,
    private val context: KafkaContext,
) : MessagingSystem, AssertsPublishing, RunnableSystemWithContext<ApplicationContext>, ExposesConfiguration {
    private val logger: Logger = LoggerFactory.getLogger(javaClass)
    private lateinit var applicationContext: ApplicationContext
    private lateinit var kafkaTemplate: KafkaTemplate<String, Any>
    private lateinit var exposedConfiguration: KafkaExposedConfiguration
    val getInterceptor = { applicationContext.getBean(TestSystemKafkaInterceptor::class.java) }
    private val state: StateOfSystem<KafkaSystem, KafkaExposedConfiguration> =
        StateOfSystem(testSystem.options, javaClass.kotlin, KafkaExposedConfiguration::class)

    override suspend fun beforeRun() {}

    override suspend fun run() {
        exposedConfiguration = state.capture {
            context.container.start()
            KafkaExposedConfiguration(context.container.bootstrapServers)
        }
    }

    override suspend fun afterRun(context: ApplicationContext) {
        applicationContext = context
        kafkaTemplate = context.getBean()
        kafkaTemplate.setProducerListener(getInterceptor())
    }

    override fun configuration(): List<String> {
        return context.configureExposedConfiguration(exposedConfiguration) + listOf(
            "kafka.bootstrapServers=${exposedConfiguration.bootstrapServers}",
            "kafka.isSecure=false"
        )
    }

    override suspend fun stop(): Unit = context.container.stop()

    override fun close(): Unit = runBlocking {
        Try {
            kafkaTemplate.destroy()
            executeWithReuseCheck { stop() }
        }.recover {
            logger.warn("got an error while closing KafkaSystem", it)
        }
    }

    override suspend fun publish(
        topic: String,
        message: Any,
        key: Option<String>,
        headers: Map<String, String>,
        testCase: Option<String>,
    ): KafkaSystem {
        val record = ProducerRecord<String, Any>(
            topic,
            0,
            key.getOrElse { "" },
            context.objectMapper.writeValueAsString(message),
            headers.toMutableMap().addTestCase(testCase).map { RecordHeader(it.key, it.value.toByteArray()) }
        )

        return kafkaTemplate.send(record).completable().await().let { this }
    }

    override suspend fun shouldBeConsumed(
        atLeastIn: Duration,
        message: Any,
    ): KafkaSystem = coroutineScope {
        shouldBeConsumedInternal(message::class, atLeastIn) { incomingMessage -> incomingMessage == Some(message) }
    }.let { this }

    override suspend fun <T : Any> shouldBeConsumedOnCondition(
        atLeastIn: Duration,
        condition: (T) -> Boolean,
        clazz: KClass<T>,
    ): MessagingSystem = coroutineScope {
        shouldBeConsumedInternal(clazz, atLeastIn) { incomingMessage -> incomingMessage.exists { o -> condition(o) } }
    }.let { this }

    private suspend fun <T : Any> shouldBeConsumedInternal(
        clazz: KClass<T>,
        atLeastIn: Duration,
        condition: (Option<T>) -> Boolean,
    ): Unit = coroutineScope { getInterceptor().waitUntilConsumed(atLeastIn, clazz, condition) }

    override suspend fun shouldBePublished(
        atLeastIn: Duration,
        message: Any,
    ): KafkaSystem = coroutineScope {
        shouldBePublishedInternal(message::class, atLeastIn) { incomingMessage -> incomingMessage == Some(message) }
    }.let { this }

    override suspend fun <T : Any> shouldBePublishedOnCondition(
        atLeastIn: Duration,
        condition: (T) -> Boolean,
        clazz: KClass<T>,
    ): KafkaSystem = coroutineScope {
        shouldBePublishedInternal(clazz, atLeastIn) { incomingMessage -> incomingMessage.exists { o -> condition(o) } }
    }.let { this }

    private suspend fun <T : Any> shouldBePublishedInternal(
        clazz: KClass<T>,
        atLeastIn: Duration,
        condition: (Option<T>) -> Boolean,
    ): Unit = coroutineScope { getInterceptor().waitUntilPublished(atLeastIn, clazz, condition) }
}

private fun (MutableMap<String, String>).addTestCase(testCase: Option<String>): MutableMap<String, String> =
    if (this.containsKey("testCase")) {
        this
    } else {
        testCase.map { this["testCase"] = it }.let { this }
    }
