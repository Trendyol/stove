package com.trendyol.stove.kafka.common

import kotlinx.coroutines.flow.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Signal-driven store shared by every Kafka integration.
 *
 * The generic record keeps transport-specific data available to its owning module while all
 * storage, replay, scoping, commit, and dump semantics remain common.
 */
class KafkaMessageStore<R : KafkaRecord> {
  private val consumed = ConcurrentHashMap<String, R>()
  private val published = ConcurrentHashMap<String, R>()
  private val committed = ConcurrentHashMap<String, R>()
  private val retried = ConcurrentHashMap<String, R>()
  private val failed = ConcurrentHashMap<String, R>()
  private val acknowledged = ConcurrentHashMap<String, R>()

  private val mutableVersion = MutableStateFlow(0L)

  val version: StateFlow<Long> = mutableVersion.asStateFlow()

  fun recordConsumed(record: R) = record(consumed, record)

  fun recordPublished(record: R) = record(published, record)

  fun recordCommitted(record: R) = record(committed, record)

  fun recordRetried(record: R) = record(retried, record)

  fun recordFailed(record: R) = record(failed, record)

  fun recordAcknowledged(record: R) = record(acknowledged, record)

  fun consumedMessages(): Collection<R> = consumed.values

  fun publishedMessages(): Collection<R> = published.values

  fun committedMessages(): Collection<R> = committed.values

  fun retriedMessages(): Collection<R> = retried.values

  fun failedMessages(): Collection<R> = failed.values

  fun acknowledgedMessages(): Collection<R> = acknowledged.values

  fun consumedRecords(): Flow<R> = replayThenLive(::consumedMessages)

  fun publishedRecords(): Flow<R> = replayThenLive(::publishedMessages)

  fun committedRecords(): Flow<R> = replayThenLive(::committedMessages)

  fun retriedRecords(): Flow<R> = replayThenLive(::retriedMessages)

  fun failedRecords(): Flow<R> = replayThenLive(::failedMessages)

  fun acknowledgedRecords(): Flow<R> = replayThenLive(::acknowledgedMessages)

  fun isCommitted(record: KafkaRecord): Boolean {
    val partition = record.partition ?: return false
    val offset = record.offset ?: return false
    return isCommitted(record.topic, partition, offset)
  }

  fun isCommitted(
    topic: String,
    partition: Int,
    offset: Long
  ): Boolean = committedMessages()
    .asSequence()
    .filter { it.topic == topic && it.partition == partition }
    .any { committedRecord -> committedRecord.offset?.let { it > offset } == true }

  fun dump(testId: String?): String {
    if (testId == null) return toString()

    val scopedConsumed = consumedMessages().filter { it.headers.belongsToTest(testId) }
    val scopedPublished = publishedMessages().filter { it.headers.belongsToTest(testId) }
    val scopedRetried = retriedMessages().filter { it.headers.belongsToTest(testId) }
    val scopedFailed = failedMessages().filter { it.headers.belongsToTest(testId) }
    val topicPartitions = scopedConsumed.map { it.topic to it.partition }.toSet()
    val publishedTopics = scopedPublished.map { it.topic }.toSet()
    val scopedCommitted = committedMessages().filter { (it.topic to it.partition) in topicPartitions }
    // Commit and acknowledgement wire records do not carry headers. Scope them through the
    // header-bearing records they describe instead of pretending they have their own test id.
    val scopedAcknowledged = acknowledgedMessages().filter { it.topic in publishedTopics }

    val hidden = (consumedMessages().size - scopedConsumed.size) +
      (publishedMessages().size - scopedPublished.size) +
      (committedMessages().size - scopedCommitted.size) +
      (retriedMessages().size - scopedRetried.size) +
      (failedMessages().size - scopedFailed.size) +
      (acknowledgedMessages().size - scopedAcknowledged.size)

    val hiddenNote = if (hidden > 0) "\n|($hidden message(s) from other tests hidden)" else ""
    return render(
      scopedConsumed,
      scopedPublished,
      scopedCommitted,
      scopedRetried,
      scopedFailed,
      scopedAcknowledged,
      hiddenNote
    )
  }

  override fun toString(): String = render(
    consumedMessages(),
    publishedMessages(),
    committedMessages(),
    retriedMessages(),
    failedMessages(),
    acknowledgedMessages()
  )

  private fun record(
    target: ConcurrentHashMap<String, R>,
    record: R
  ) {
    target[record.id] = record
    mutableVersion.update { it + 1 }
  }

  private fun replayThenLive(query: () -> Collection<R>): Flow<R> = flow {
    val seen = HashSet<String>()
    version.collect {
      query().forEach { record -> if (seen.add(record.id)) emit(record) }
    }
  }

  private fun render(
    consumed: Collection<R>,
    published: Collection<R>,
    committed: Collection<R>,
    retried: Collection<R>,
    failed: Collection<R>,
    acknowledged: Collection<R>,
    suffix: String = ""
  ): String = """
    |Consumed: $consumed
    |Published: $published
    |Committed: $committed
    |Retried: $retried
    |Failed: $failed
    |Acknowledged: $acknowledged$suffix
  """.trimIndent().trimMargin()
}
