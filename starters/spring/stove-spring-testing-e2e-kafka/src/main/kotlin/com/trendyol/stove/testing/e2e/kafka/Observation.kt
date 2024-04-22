package com.trendyol.stove.testing.e2e.kafka

import arrow.core.Option

data class MessageMetadata(
  val topic: String,
  val key: String,
  val headers: Map<String, Any>
)

@PublishedApi
internal data class ParsedMessage<T>(
  val message: Option<T>,
  val metadata: MessageMetadata
)

@PublishedApi
internal data class FailedParsedMessage<T>(
  val message: ParsedMessage<T>,
  val reason: Throwable
)

@KafkaDsl
open class ObservedMessage<T>(
  open val actual: T,
  open val metadata: MessageMetadata
)

@KafkaDsl
data class FailedObservedMessage<T>(
  override val actual: T,
  override val metadata: MessageMetadata,
  val reason: Throwable
) : ObservedMessage<T>(actual, metadata)

@KafkaDsl
data class Failure<T>(
  val message: ObservedMessage<T>,
  val reason: Throwable
)
