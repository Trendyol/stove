package com.trendyol.stove.testing.e2e.kafka

import arrow.core.*
import com.trendyol.stove.functional.*
import com.trendyol.stove.testing.e2e.messaging.*
import com.trendyol.stove.testing.e2e.system.TestSystem
import com.trendyol.stove.testing.e2e.system.TestSystemOptions.Companion.createStateStorage
import com.trendyol.stove.testing.e2e.system.abstractions.*
import kotlinx.coroutines.*
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.header.internals.RecordHeader
import org.slf4j.*
import org.springframework.beans.factory.getBean
import org.springframework.context.ApplicationContext
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.listener.RecordInterceptor
import kotlin.reflect.KClass
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

@KafkaDsl
class KafkaSystem(
  override val testSystem: TestSystem,
  private val context: KafkaContext
) : PluggedSystem, RunnableSystemWithContext<ApplicationContext>, ExposesConfiguration {
  private val logger: Logger = LoggerFactory.getLogger(javaClass)
  private lateinit var applicationContext: ApplicationContext
  private lateinit var kafkaTemplate: KafkaTemplate<Any, Any>
  private lateinit var exposedConfiguration: KafkaExposedConfiguration
  val getInterceptor: () -> TestSystemKafkaInterceptor<Any, Any> = { applicationContext.getBean() }
  private val state: StateStorage<KafkaExposedConfiguration> =
    testSystem.options.createStateStorage<KafkaExposedConfiguration, KafkaSystem>()

  override suspend fun beforeRun() = Unit

  override suspend fun run() {
    exposedConfiguration = state.capture {
      context.container.start()
      KafkaExposedConfiguration(context.container.bootstrapServers)
    }
  }

  override suspend fun afterRun(context: ApplicationContext) {
    applicationContext = context
    checkIfInterceptorConfiguredProperly(context)
    kafkaTemplate = context.getBean()
    kafkaTemplate.setProducerListener(getInterceptor())
  }

  private fun checkIfInterceptorConfiguredProperly(context: ApplicationContext) {
    val registeredInterceptor: RecordInterceptor<*, *> = context.getBean()
    if (registeredInterceptor !is TestSystemKafkaInterceptor) {
      throw AssertionError(
        "Kafka interceptor is not an instance of TestSystemKafkaInterceptor, " +
          "please make sure that you have configured the Stove Kafka interceptor in your Spring Application properly." +
          "You can use a TestSystemInitializer to add the interceptor to your Spring Application: " +
          """
              fun SpringApplication.addTestSystemDependencies() {
                this.addInitializers(TestSystemInitializer())
              }
  
              class TestSystemInitializer : BaseApplicationContextInitializer({
                bean<TestSystemKafkaInterceptor<*, *>>(isPrimary = true)
              })            
          """.trimIndent()
      )
    }
  }

  @KafkaDsl
  suspend fun publish(
    topic: String,
    message: Any,
    key: Option<String> = None,
    partition: Option<Int> = None,
    headers: Map<String, String> = mapOf(),
    testCase: Option<String> = None
  ): KafkaSystem {
    val record = ProducerRecord<String, Any>(
      topic,
      partition.getOrNull(),
      key.getOrNull(),
      context.objectMapper.writeValueAsString(message),
      headers.toMutableMap().addTestCase(testCase).map { RecordHeader(it.key, it.value.toByteArray()) }
    )

    return context.options.ops.send(kafkaTemplate, record).let { this }
  }

  @KafkaDsl
  suspend inline fun <reified T : Any> shouldBeConsumed(
    atLeastIn: Duration = 5.seconds,
    crossinline condition: ObservedMessage<T>.() -> Boolean
  ): KafkaSystem = coroutineScope {
    shouldBeConsumedInternal(T::class, atLeastIn) { parsed ->
      parsed.message.isSome { o -> condition(ObservedMessage(o, parsed.metadata)) }
    }
  }.let { this }

  @KafkaDsl
  suspend inline fun <reified T : Any> shouldBeFailed(
    atLeastIn: Duration = 5.seconds,
    crossinline condition: FailedObservedMessage<T>.() -> Boolean
  ): KafkaSystem = coroutineScope {
    shouldBeFailedInternal(T::class, atLeastIn) { parsed ->
      parsed as FailedParsedMessage<T>
      parsed.message.isSome { o ->
        condition(
          FailedObservedMessage(
            o,
            parsed.metadata,
            parsed.reason
          )
        )
      }
    }
  }.let { this }

  @KafkaDsl
  suspend inline fun <reified T : Any> shouldBePublished(
    atLeastIn: Duration = 5.seconds,
    crossinline condition: ObservedMessage<T>.() -> Boolean
  ): KafkaSystem = coroutineScope {
    shouldBePublishedInternal(T::class, atLeastIn) { parsed ->
      parsed.message.isSome { o -> condition(ObservedMessage(o, parsed.metadata)) }
    }
  }.let { this }

  @PublishedApi
  internal suspend fun <T : Any> shouldBeConsumedInternal(
    clazz: KClass<T>,
    atLeastIn: Duration,
    condition: (message: ParsedMessage<T>) -> Boolean
  ): Unit = coroutineScope { getInterceptor().waitUntilConsumed(atLeastIn, clazz, condition) }

  @PublishedApi
  internal suspend fun <T : Any> shouldBeFailedInternal(
    clazz: KClass<T>,
    atLeastIn: Duration,
    condition: (message: ParsedMessage<T>) -> Boolean
  ): Unit = coroutineScope { getInterceptor().waitUntilFailed(atLeastIn, clazz, condition) }

  @PublishedApi
  internal suspend fun <T : Any> shouldBePublishedInternal(
    clazz: KClass<T>,
    atLeastIn: Duration,
    condition: (message: ParsedMessage<T>) -> Boolean
  ): Unit = coroutineScope { getInterceptor().waitUntilPublished(atLeastIn, clazz, condition) }

  override fun configuration(): List<String> = context.options.configureExposedConfiguration(exposedConfiguration)

  override suspend fun stop(): Unit = context.container.stop()

  override fun close(): Unit = runBlocking {
    Try {
      kafkaTemplate.destroy()
      executeWithReuseCheck { stop() }
    }.recover {
      logger.warn("got an error while closing KafkaSystem", it)
    }
  }
}
