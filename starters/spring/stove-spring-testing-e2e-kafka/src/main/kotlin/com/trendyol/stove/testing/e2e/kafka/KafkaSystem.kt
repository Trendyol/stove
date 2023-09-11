package com.trendyol.stove.testing.e2e.kafka

import arrow.core.None
import arrow.core.Option
import arrow.core.Some
import arrow.core.getOrElse
import com.trendyol.stove.functional.Try
import com.trendyol.stove.functional.recover
import com.trendyol.stove.testing.e2e.system.TestSystem
import com.trendyol.stove.testing.e2e.system.abstractions.ExposesConfiguration
import com.trendyol.stove.testing.e2e.system.abstractions.PluggedSystem
import com.trendyol.stove.testing.e2e.system.abstractions.RunnableSystemWithContext
import com.trendyol.stove.testing.e2e.system.abstractions.StateOfSystem
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
import kotlin.reflect.KClass
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class KafkaSystem(
    override val testSystem: TestSystem,
    private val context: KafkaContext
) : PluggedSystem, RunnableSystemWithContext<ApplicationContext>, ExposesConfiguration {
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

    override fun configuration(): List<String> = context.configureExposedConfiguration(exposedConfiguration) + listOf(
        "kafka.bootstrapServers=${exposedConfiguration.bootstrapServers}",
        "kafka.isSecure=false"
    )

    override suspend fun stop(): Unit = context.container.stop()

    override fun close(): Unit = runBlocking {
        Try {
            kafkaTemplate.destroy()
            executeWithReuseCheck { stop() }
        }.recover {
            logger.warn("got an error while closing KafkaSystem", it)
        }
    }

    suspend fun publish(
        topic: String,
        message: Any,
        key: Option<String> = None,
        headers: Map<String, String> = mapOf(),
        testCase: Option<String> = None
    ): KafkaSystem {
        val record = ProducerRecord<String, Any>(
            topic,
            0,
            key.getOrElse { "" },
            context.objectMapper.writeValueAsString(message),
            headers.toMutableMap().addTestCase(testCase).map { RecordHeader(it.key, it.value.toByteArray()) }
        )
        return kafkaTemplate.usingCompletableFuture().send(record).await().let { this }
    }

    suspend fun shouldBeConsumed(
        atLeastIn: Duration = 5.seconds,
        message: Any
    ): KafkaSystem = coroutineScope {
        shouldBeConsumedInternal(message::class, atLeastIn) { incomingMessage -> incomingMessage == Some(message) }
    }.let { this }

    suspend inline fun <reified T : Any> shouldBeConsumed(
        atLeastIn: Duration = 5.seconds,
        crossinline condition: (T) -> Boolean
    ): KafkaSystem = coroutineScope {
        shouldBeConsumedInternal(T::class, atLeastIn) { incomingMessage -> incomingMessage.isSome { o -> condition(o) } }
    }.let { this }

    @Deprecated("Use shouldBeConsumed instead", ReplaceWith("shouldBeConsumed<T> { actual -> actual == expected }"))
    suspend inline fun <reified T : Any> shouldBeConsumedOnCondition(
        atLeastIn: Duration = 5.seconds,
        crossinline condition: (T) -> Boolean
    ): KafkaSystem = coroutineScope {
        shouldBeConsumedInternal(T::class, atLeastIn) { incomingMessage -> incomingMessage.isSome { o -> condition(o) } }
    }.let { this }

    suspend fun shouldBeFailed(
        atLeastIn: Duration = 5.seconds,
        message: Any,
        exception: Throwable
    ): KafkaSystem = coroutineScope {
        shouldBeFailedInternal(message::class, atLeastIn) { option, throwable -> option == Some(message) && throwable == exception }
    }.let { this }

    suspend inline fun <reified T : Any> shouldBeFailed(
        atLeastIn: Duration = 5.seconds,
        crossinline condition: (T, Throwable) -> Boolean
    ): KafkaSystem = coroutineScope {
        shouldBeFailedInternal(T::class, atLeastIn) { message, throwable -> message.isSome { m -> condition(m, throwable) } }
    }.let { this }

    @Deprecated(
        "Use shouldBeFailed instead",
        ReplaceWith("shouldBeFailed<T> { actual, throwable -> actual == expected && throwable == exception }")
    )
    suspend inline fun <reified T : Any> shouldBeFailedOnCondition(
        atLeastIn: Duration = 5.seconds,
        crossinline condition: (T, Throwable) -> Boolean
    ): KafkaSystem = coroutineScope {
        shouldBeFailedInternal(T::class, atLeastIn) { message, throwable -> message.isSome { m -> condition(m, throwable) } }
    }.let { this }

    suspend fun shouldBePublished(
        atLeastIn: Duration = 5.seconds,
        message: Any
    ): KafkaSystem = coroutineScope {
        shouldBePublishedInternal(message::class, atLeastIn) { incomingMessage -> incomingMessage == Some(message) }
    }.let { this }

    suspend inline fun <reified T : Any> shouldBePublished(
        atLeastIn: Duration = 5.seconds,
        crossinline condition: (T) -> Boolean
    ): KafkaSystem = coroutineScope {
        shouldBePublishedInternal(T::class, atLeastIn) { incomingMessage -> incomingMessage.isSome { o -> condition(o) } }
    }.let { this }

    @Deprecated("Use shouldBePublished instead", ReplaceWith("shouldBePublished<T> { actual -> actual == expected }"))
    suspend inline fun <reified T : Any> shouldBePublishedOnCondition(
        atLeastIn: Duration = 5.seconds,
        crossinline condition: (T) -> Boolean
    ): KafkaSystem = coroutineScope {
        shouldBePublishedInternal(T::class, atLeastIn) { incomingMessage -> incomingMessage.isSome { o -> condition(o) } }
    }.let { this }

    @PublishedApi
    internal suspend fun <T : Any> shouldBeConsumedInternal(
        clazz: KClass<T>,
        atLeastIn: Duration,
        condition: (Option<T>) -> Boolean
    ): Unit = coroutineScope { getInterceptor().waitUntilConsumed(atLeastIn, clazz, condition) }

    @PublishedApi
    internal suspend fun <T : Any> shouldBeFailedInternal(
        clazz: KClass<T>,
        atLeastIn: Duration,
        condition: (Option<T>, Throwable) -> Boolean
    ): Unit = coroutineScope { getInterceptor().waitUntilFailed(atLeastIn, clazz, condition) }

    @PublishedApi
    internal suspend fun <T : Any> shouldBePublishedInternal(
        clazz: KClass<T>,
        atLeastIn: Duration,
        condition: (Option<T>) -> Boolean
    ): Unit = coroutineScope { getInterceptor().waitUntilPublished(atLeastIn, clazz, condition) }
}

private fun (MutableMap<String, String>).addTestCase(testCase: Option<String>): MutableMap<String, String> =
    if (this.containsKey("testCase")) this else testCase.map { this["testCase"] = it }.let { this }
