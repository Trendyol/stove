package com.trendyol.stove.testing.e2e.standalone.kafka

import com.trendyol.stove.functional.Try
import com.trendyol.stove.functional.recover
import com.trendyol.stove.testing.e2e.standalone.kafka.intercepting.TestSystemKafkaInterceptor
import com.trendyol.stove.testing.e2e.system.abstractions.SystemConfigurationException
import io.github.nomisRev.kafka.receiver.KafkaReceiver
import kotlinx.coroutines.*
import org.apache.kafka.clients.admin.Admin
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class SubscribeToAll(
  private val adminClient: Admin,
  private val receiver: KafkaReceiver<String, Any>,
  private val interceptor: TestSystemKafkaInterceptor
) : AutoCloseable {
  private lateinit var subscription: Job
  private val logger: Logger = LoggerFactory.getLogger(javaClass)

  @OptIn(DelicateCoroutinesApi::class)
  suspend fun start() =
    coroutineScope {
      val topics = adminClient.listTopics().names().get()
      if (!topics.any()) {
        throw SystemConfigurationException(
          KafkaSystem::class,
          "Topics are not found, please enable creating topics before running e2e tests on them. " +
            "Stove depends on created topics to be able to understand what is consumed and published"
        )
      }

      subscription =
        GlobalScope.launch {
          while (!subscription.isCancelled) {
            receiver.withConsumer { consumer ->
              receiver
                .receive(topics)
                .collect {
                  Try { interceptor.onMessage(it, consumer) }
                    .recover { logger.warn("$javaClass got an exception: $it") }
                }
            }
          }
        }
    }

  override fun close() {
    Try { subscription.cancel() }
  }
}
