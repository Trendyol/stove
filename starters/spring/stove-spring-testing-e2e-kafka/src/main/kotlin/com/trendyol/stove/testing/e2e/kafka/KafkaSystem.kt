package com.trendyol.stove.testing.e2e.kafka

import arrow.core.Option
import arrow.core.Some
import arrow.core.getOrElse
import com.fasterxml.jackson.databind.ObjectMapper
import com.trendyol.stove.testing.e2e.containers.DEFAULT_REGISTRY
import com.trendyol.stove.testing.e2e.containers.withProvidedRegistry
import com.trendyol.stove.testing.e2e.messaging.AssertsPublishing
import com.trendyol.stove.testing.e2e.messaging.MessagingSystem
import com.trendyol.stove.testing.e2e.system.TestSystem
import com.trendyol.stove.testing.e2e.system.abstractions.ExposesConfiguration
import com.trendyol.stove.testing.e2e.system.abstractions.RunnableSystemWithContext
import com.trendyol.stove.testing.e2e.system.abstractions.SystemNotRegisteredException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.future.await
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.header.internals.RecordHeader
import org.springframework.beans.factory.getBean
import org.springframework.context.ApplicationContext
import org.springframework.kafka.core.KafkaTemplate
import org.testcontainers.containers.KafkaContainer
import kotlin.reflect.KClass
import kotlin.time.Duration

data class KafkaSystemOptions(
    val ports: List<Int> = listOf(9092, 9093),
)

fun TestSystem.withKafka(
    registry: String = DEFAULT_REGISTRY,
    options: KafkaSystemOptions = KafkaSystemOptions(),
    configureExposedConfiguration: (KafkaExposedConfiguration) -> List<String> = { _ -> listOf() },
): TestSystem {
    val kafka =
        withProvidedRegistry("confluentinc/cp-kafka:latest", registry) {
            KafkaContainer(it).withExposedPorts(*options.ports.toTypedArray()).withEmbeddedZookeeper()
        }
    getOrRegister(KafkaSystem(this, KafkaContext(kafka, configureExposedConfiguration)))
    return this
}

data class KafkaExposedConfiguration(
    val boostrapServers: String,
)

data class KafkaContext(
    val container: KafkaContainer,
    val configureExposedConfiguration: (KafkaExposedConfiguration) -> List<String>,
)

fun TestSystem.kafka(): KafkaSystem =
    getOrNone<KafkaSystem>().getOrElse { throw SystemNotRegisteredException(KafkaSystem::class) }

class KafkaSystem(
    override val testSystem: TestSystem,
    private val context: KafkaContext,
) : MessagingSystem, AssertsPublishing, RunnableSystemWithContext<ApplicationContext>, ExposesConfiguration {
    private lateinit var applicationContext: ApplicationContext
    private lateinit var objectMapper: ObjectMapper
    private lateinit var kafkaTemplate: KafkaTemplate<String, Any>
    val getInterceptor = { applicationContext.getBean(TestSystemKafkaInterceptor::class.java) }

    override suspend fun beforeRun() {}

    override suspend fun run(): Unit = context.container.start()

    override suspend fun afterRun(context: ApplicationContext) {
        applicationContext = context
        objectMapper = context.getBean("objectMapper", ObjectMapper::class.java)
        kafkaTemplate = context.getBean()
        kafkaTemplate.setProducerListener(getInterceptor())
    }

    override fun configuration(): List<String> {
        return context.configureExposedConfiguration(
            KafkaExposedConfiguration(context.container.bootstrapServers)
        ) + listOf("kafka.bootstrapServers=${context.container.bootstrapServers}", "kafka.isSecure=false")
    }

    override suspend fun stop() = context.container.stop()

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
            objectMapper.writeValueAsString(message),
            headers.map { RecordHeader(it.key, it.value.toByteArray()) }
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
    if (this.containsKey("testCase")) this
    else testCase.map { this["testCase"] = it }.let { this }
