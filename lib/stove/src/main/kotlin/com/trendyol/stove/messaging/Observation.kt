package com.trendyol.stove.messaging

import arrow.core.Option
import com.trendyol.stove.system.annotations.StoveDsl

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
