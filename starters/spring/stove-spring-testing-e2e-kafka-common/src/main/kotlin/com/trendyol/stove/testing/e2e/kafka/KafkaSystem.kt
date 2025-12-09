package com.trendyol.stove.testing.e2e.kafka

import arrow.core.*
import com.trendyol.stove.functional.*
import com.trendyol.stove.testing.e2e.messaging.*
import com.trendyol.stove.testing.e2e.serialization.StoveSerde
import com.trendyol.stove.testing.e2e.system.*
import com.trendyol.stove.testing.e2e.system.abstractions.*
import kotlinx.coroutines.*
import org.apache.kafka.clients.admin.*
import org.apache.kafka.clients.producer.*
import org.apache.kafka.common.header.internals.RecordHeader
import org.slf4j.*
import org.springframework.beans.factory.*
import org.springframework.context.ApplicationContext
import org.springframework.kafka.core.*
import org.springframework.kafka.listener.RecordInterceptor
import kotlin.reflect.KClass
import kotlin.time.*
import kotlin.time.Duration.Companion.seconds

@KafkaDsl
@Suppress("TooManyFunctions", "unused")
class KafkaSystem(
  override val testSystem: TestSystem,
  private val context: KafkaContext
) : PluggedSystem,
  RunnableSystemWithContext<ApplicationContext>,
  ExposesConfiguration {
  private val logger: Logger = LoggerFactory.getLogger(javaClass)
  private lateinit var applicationContext: ApplicationContext
  private lateinit var kafkaTemplate: KafkaTemplate<Any, Any>
  private lateinit var exposedConfiguration: KafkaExposedConfiguration
  private lateinit var admin: Admin
  val getInterceptor: () -> TestSystemKafkaInterceptor<Any, Any> = { applicationContext.getBean() }
  private val state: StateStorage<KafkaExposedConfiguration> =
    testSystem.options.createStateStorage<KafkaExposedConfiguration, KafkaSystem>()

  /**
   * Publishes a message to the given topic.
   * The message will be serialized using the provided serde.
   *
   * If the KafkaTemplate of the application is desired to be used, then [BridgeSystem] functionality can be used.
   * For example:
   * ```kotlin
   * validate {
   *   using<KafkaTemplate<Any, Any>> {
   *      this.send(ProducerRecord("topic", "message"))
   *   }
   * }
   * ```
   * [BridgeSystem] should be enabled while configuring the [TestSystem].
   * @param topic The topic to publish the message to.
   * @param message The message to publish.
   * @param key The key of the message.
   * @param partition The partition to publish the message to.
   * @param headers The headers of the message.
   * @param serde The serde to serialize the message.
   * @param testCase The test case of the message.
   * @return KafkaSystem
   */
  @KafkaDsl
  suspend fun publish(
    topic: String,
    message: Any,
    key: Option<String> = None,
    partition: Option<Int> = None,
    headers: Map<String, String> = mapOf(),
    serde: Option<StoveSerde<Any, *>> = None,
    testCase: Option<String> = None
  ): KafkaSystem {
    val record = ProducerRecord<String, Any>(
      topic,
      partition.getOrNull(),
      key.getOrNull(),
      message,
      headers.toMutableMap().addTestCase(testCase).map { RecordHeader(it.key, it.value.toByteArray()) }
    )

    return context.options.ops
      .send(kafkaTemplate, record)
      .let { this }
  }

  /**
   * Admin operations for Kafka.
   */
  @KafkaDsl
  suspend fun adminOperations(block: suspend Admin.() -> Unit) = block(admin)

  /**
   * Asserts that a message is consumed.
   */
  @KafkaDsl
  suspend inline fun <reified T : Any> shouldBeConsumed(
    atLeastIn: Duration = 5.seconds,
    crossinline condition: ObservedMessage<T>.() -> Boolean
  ): KafkaSystem = coroutineScope {
    shouldBeConsumedInternal(T::class, atLeastIn) { parsed ->
      parsed.message.isSome { o -> condition(ObservedMessage(o, parsed.metadata)) }
    }
  }.let { this }

  /**
   * Asserts that a message is failed.
   */
  @KafkaDsl
  suspend inline fun <reified T : Any> shouldBeFailed(
    atLeastIn: Duration = 5.seconds,
    crossinline condition: FailedObservedMessage<T>.() -> Boolean
  ): KafkaSystem = coroutineScope {
    shouldBeFailedInternal(T::class, atLeastIn) { parsed ->
      parsed as FailedParsedMessage<T>
      parsed.message.isSome { o -> condition(FailedObservedMessage(o, parsed.metadata, parsed.reason)) }
    }
  }.let { this }

  /**
   * Asserts that a message is published.
   */
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

  /**
   * Pauses the container. Use with care, as it will pause the container which might affect other tests.
   * This operation is not supported when using a provided instance.
   * @return KafkaSystem
   */
  @KafkaDsl
  fun pause(): KafkaSystem = withContainerOrWarn("pause") { it.pause() }

  /**
   * Unpauses the container. Use with care, as it will pause the container which might affect other tests.
   * This operation is not supported when using a provided instance.
   * @return KafkaSystem
   */
  @KafkaDsl
  fun unpause(): KafkaSystem = withContainerOrWarn("unpause") { it.unpause() }

  override suspend fun stop(): Unit = whenContainer { it.stop() }

  override fun close(): Unit = runBlocking {
    Try {
      context.options.cleanup(admin)
      kafkaTemplate.destroy()
      executeWithReuseCheck { stop() }
    }.recover {
      logger.warn("got an error while closing KafkaSystem", it)
    }
  }

  override suspend fun beforeRun() = Unit

  override suspend fun run() {
    exposedConfiguration = obtainExposedConfiguration()
  }

  override suspend fun afterRun(context: ApplicationContext) {
    applicationContext = context
    checkIfInterceptorConfiguredProperly(context)
    kafkaTemplate = createKafkaTemplate(context, exposedConfiguration)
    admin = createAdminClient(exposedConfiguration)
    runMigrationsIfNeeded()
  }

  private suspend fun obtainExposedConfiguration(): KafkaExposedConfiguration =
    when {
      context.options is ProvidedKafkaSystemOptions -> context.options.config
      context.runtime is StoveKafkaContainer -> startKafkaContainer(context.runtime)
      else -> throw UnsupportedOperationException("Unsupported runtime type: ${context.runtime::class}")
    }

  private suspend fun startKafkaContainer(container: StoveKafkaContainer): KafkaExposedConfiguration =
    state.capture {
      container.start()
      KafkaExposedConfiguration(container.bootstrapServers)
    }

  private suspend fun runMigrationsIfNeeded() {
    if (shouldRunMigrations()) {
      context.options.migrationCollection.run(KafkaMigrationContext(admin, context.options))
    }
  }

  private fun shouldRunMigrations(): Boolean = when {
    context.options is ProvidedKafkaSystemOptions -> context.options.runMigrations
    context.runtime is StoveKafkaContainer -> !state.isSubsequentRun() || testSystem.options.runMigrationsAlways
    else -> throw UnsupportedOperationException("Unsupported runtime type: ${context.runtime::class}")
  }

  private fun createAdminClient(
    exposedConfiguration: KafkaExposedConfiguration
  ): Admin = mapOf<String, Any>(
    AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG to exposedConfiguration.bootstrapServers,
    AdminClientConfig.CLIENT_ID_CONFIG to "stove-kafka-admin-client"
  ).let { Admin.create(it) }

  private fun createKafkaTemplate(
    context: ApplicationContext,
    exposedConfiguration: KafkaExposedConfiguration
  ): KafkaTemplate<Any, Any> {
    val kafkaTemplates: Map<String, KafkaTemplate<Any, Any>> = context.getBeansOfType()
    return kafkaTemplates
      .values
      .onEach {
        it.setProducerListener(getInterceptor())
        it.setCloseTimeout(1.seconds.toJavaDuration())
      }.firstOrNone { safeContains(it, exposedConfiguration) }
      .getOrElse {
        logger.warn("No KafkaTemplate found for the configured bootstrap servers, using a fallback KafkaTemplate")
        createFallbackTemplate(exposedConfiguration)
      }
  }

  @Suppress("UNCHECKED_CAST")
  private fun safeContains(
    kafkaTemplate: KafkaTemplate<Any, Any>,
    exposedConfiguration: KafkaExposedConfiguration
  ): Boolean = kafkaTemplate.producerFactory.configurationProperties[ProducerConfig.BOOTSTRAP_SERVERS_CONFIG]
    .toOption()
    .map {
      when (it) {
        is String -> it
        is Iterable<*> -> (it as Iterable<String>).joinToString(",")
        else -> it.toString()
      }
    }.isSome { it.contains(exposedConfiguration.bootstrapServers) }

  private fun createFallbackTemplate(exposedConfiguration: KafkaExposedConfiguration): KafkaTemplate<Any, Any> {
    val producerFactory = DefaultKafkaProducerFactory<Any, Any>(
      mapOf(
        ProducerConfig.BOOTSTRAP_SERVERS_CONFIG to exposedConfiguration.bootstrapServers,
        ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG to context.options.fallbackSerde.keySerializer::class.java,
        ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG to context.options.fallbackSerde.valueSerializer::class.java
      )
    )
    val fallbackTemplate = KafkaTemplate(producerFactory).also {
      it.setProducerListener(getInterceptor())
      it.setCloseTimeout(1.seconds.toJavaDuration())
    }
    return fallbackTemplate
  }

  private fun checkIfInterceptorConfiguredProperly(context: ApplicationContext) {
    val interceptors: Map<String, RecordInterceptor<*, *>> = context.getBeansOfType()

    fun stoveInterceptionPresent(): Boolean = interceptors.values.any { it is TestSystemKafkaInterceptor<*, *> }
    if (!stoveInterceptionPresent()) {
      throw AssertionError(
        "Kafka interceptor is not an instance of TestSystemKafkaInterceptor, " +
          "please make sure that you have configured the Stove Kafka interceptor in your Spring Application properly." +
          "You can use stoveSpringRegistrar to add the interceptor to your Spring Application: " +
          """
              TestAppRunner.run(params) {
                addInitializers(
                  stoveSpringRegistrar {
                    bean<TestSystemKafkaInterceptor<*, *>>(isPrimary = true)
                  }
                )
              }          
          """.trimIndent()
      )
    }
  }

  private inline fun withContainerOrWarn(
    operation: String,
    action: (StoveKafkaContainer) -> Unit
  ): KafkaSystem = when (val runtime = context.runtime) {
    is StoveKafkaContainer -> {
      action(runtime)
      this
    }

    is ProvidedRuntime -> {
      logger.warn("$operation() is not supported when using a provided instance")
      this
    }

    else -> {
      throw UnsupportedOperationException("Unsupported runtime type: ${runtime::class}")
    }
  }

  private inline fun whenContainer(action: (StoveKafkaContainer) -> Unit) {
    if (context.runtime is StoveKafkaContainer) {
      action(context.runtime)
    }
  }

  companion object {
    fun KafkaSystem.kafkaTemplate(): KafkaTemplate<Any, Any> = kafkaTemplate
  }
}
