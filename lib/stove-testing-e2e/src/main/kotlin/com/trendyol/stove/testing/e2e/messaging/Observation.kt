package com.trendyol.stove.testing.e2e.messaging

import arrow.core.Option
import com.trendyol.stove.testing.e2e.system.annotations.StoveDsl
import kotlin.reflect.KClass

data class MessageMetadata(
  val topic: String,
  val key: String,
  val headers: Map<String, Any>
)

sealed interface ParsedMessage<T> {
  val message: Option<T>
  val metadata: MessageMetadata
}

class SuccessfulParsedMessage<T>(
  override val message: Option<T>,
  override val metadata: MessageMetadata
) : ParsedMessage<T>

class FailedParsedMessage<T>(
  override val message: Option<T>,
  override val metadata: MessageMetadata,
  val reason: Throwable
) : ParsedMessage<T>

@StoveDsl
open class ObservedMessage<T>(
  open val actual: T,
  open val metadata: MessageMetadata
)

@StoveDsl
data class FailedObservedMessage<T>(
  override val actual: T,
  override val metadata: MessageMetadata,
  val reason: Throwable
) : ObservedMessage<T>(actual, metadata)

@StoveDsl
data class Failure<T>(
  val message: ObservedMessage<T>,
  val reason: Throwable
)

data class MessagingAssertion<T : Any>(
  val clazz: KClass<T>,
  val condition: (ParsedMessage<T>) -> Boolean
)
