package com.trendyol.stove.testing.e2e.standalone.kafka

data class PublishedRecord(
  val topic: String,
  val key: String,
  val value: String,
  val headers: Map<String, String>
)

data class CommittedRecord(
  val topic: String,
  val metadata: String,
  val offset: Long,
  val partition: Int
)

data class ConsumedRecord(
  val topic: String,
  val key: String,
  val value: String,
  val headers: Map<String, String>,
  val offsets: List<Long>,
  val offset: Long,
  val partition: Int
)
