package com.trendyol.stove.testing.e2e.standalone.kafka

class PublishedRecord(
  val topic: String,
  val key: String,
  val value: ByteArray,
  val headers: Map<String, String>
)

class CommittedRecord(
  val topic: String,
  val metadata: String,
  val offset: Long,
  val partition: Int
)

class ConsumedRecord(
  val topic: String,
  val key: String,
  val value: ByteArray,
  val headers: Map<String, String>,
  val offset: Long,
  val partition: Int
)
