package com.trendyol.stove.testing.e2e.kafka

import com.trendyol.stove.testing.e2e.messaging.MessageMetadata

internal sealed class StoveMessage {
  abstract val topic: String
  abstract val value: String
  abstract val metadata: MessageMetadata
  abstract val partition: Int?
  abstract val key: String?
  abstract val timeStamp: Long?

  internal data class StoveConsumedMessage(
    override val topic: String,
    override val value: String,
    override val metadata: MessageMetadata,
    val offset: Long?,
    override val partition: Int?,
    override val key: String?,
    override val timeStamp: Long?
  ) : StoveMessage()

  internal data class StovePublishedMessage(
    override val topic: String,
    override val value: String,
    override val metadata: MessageMetadata,
    override val partition: Int?,
    override val key: String?,
    override val timeStamp: Long?
  ) : StoveMessage()
}
