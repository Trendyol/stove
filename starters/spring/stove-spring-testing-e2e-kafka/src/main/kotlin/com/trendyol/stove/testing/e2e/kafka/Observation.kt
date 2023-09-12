package com.trendyol.stove.testing.e2e.kafka

import arrow.core.Option

data class MessageMetadata(
    val topic: String,
    val key: String,
    val headers: Map<String, Any>
)

data class ParsedMessage<T>(
    val message: Option<T>,
    val metadata: MessageMetadata
)

data class FailedParsedMessage<T>(
    val message: ParsedMessage<T>,
    val reason: Throwable
)

open class ObservedMessage<T>(
    open val actual: T,
    open val metadata: MessageMetadata
)

data class FailedObservedMessage<T>(
    override val actual: T,
    override val metadata: MessageMetadata,
    val reason: Throwable
) : ObservedMessage<T>(actual, metadata)

data class Failure<T>(
    val message: ObservedMessage<T>,
    val reason: Throwable
)
