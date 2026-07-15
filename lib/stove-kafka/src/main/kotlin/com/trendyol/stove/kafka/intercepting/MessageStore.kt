package com.trendyol.stove.kafka.intercepting

import com.trendyol.stove.kafka.*
import com.trendyol.stove.tracing.TraceContext
import io.exoquery.pprint
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*

/**
 * Events emitted by [MessageStore] whenever a message is recorded.
 *
 * Subscribe via [MessageStore.events] to react to Kafka activity as it is observed,
 * instead of polling the store.
 */
sealed interface StoveMessageEvent {
  data class Consumed(val message: ConsumedMessage) : StoveMessageEvent

  data class Published(val message: PublishedMessage) : StoveMessageEvent

  data class Committed(val message: CommittedMessage) : StoveMessageEvent

  data class Retried(val message: ConsumedMessage) : StoveMessageEvent

  data class Failed(val message: ConsumedMessage) : StoveMessageEvent

  data class Acknowledged(val message: AcknowledgedMessage) : StoveMessageEvent
}

/**
 * Returns true when the message headers do not exclude it from the given test scope.
 *
 * Fail-open by design: a message is only excluded when it is *provably* tagged with a
 * different test id. Untagged messages always match, because the application may publish
 * without any propagation in place (no OTel agent, no manual header copying) and Stove
 * must keep observing those messages exactly as before.
 *
 * The tag is read from either transport Stove uses:
 * - [TraceContext.STOVE_TEST_ID_HEADER], injected by Stove's own publish (no OTel needed;
 *   Kafka carries it into the app's consumed records),
 * - the W3C `baggage` header ([TraceContext.BAGGAGE_TEST_ID_KEY]), present on app-published
 *   messages when the OTel agent propagates context.
 */
internal fun Map<String, String>.belongsToTest(testId: String?): Boolean {
  if (testId == null) return true
  val tagged = stoveTestId() ?: return true
  return tagged == testId
}

/**
 * Extracts the Stove test id from message headers, or null when the message is untagged.
 * Header keys are matched case-insensitively; baggage values are percent-decoded.
 */
internal fun Map<String, String>.stoveTestId(): String? {
  entries
    .firstOrNull { it.key.equals(TraceContext.STOVE_TEST_ID_HEADER, ignoreCase = true) }
    ?.value
    ?.takeIf { it.isNotBlank() }
    ?.let { return it }
  val baggage = entries.firstOrNull { it.key.equals(BAGGAGE_HEADER, ignoreCase = true) }?.value ?: return null
  return parseBaggageEntry(baggage, TraceContext.BAGGAGE_TEST_ID_KEY)
}

private const val BAGGAGE_HEADER = "baggage"

private fun parseBaggageEntry(baggage: String, key: String): String? = baggage
  .split(',')
  .asSequence()
  .map { it.trim().substringBefore(';') }
  .mapNotNull { entry ->
    val separator = entry.indexOf('=')
    if (separator <= 0) null else entry.take(separator).trim() to entry.substring(separator + 1).trim()
  }.firstOrNull { (entryKey, _) -> entryKey == key }
  ?.second
  ?.let(::percentDecode)
  ?.takeIf { it.isNotBlank() }

private fun percentDecode(value: String): String? = runCatching {
  // URLDecoder turns '+' into a space, which W3C baggage percent-encoding does not;
  // protect literal plus signs before decoding.
  java.net.URLDecoder.decode(value.replace("+", "%2B"), Charsets.UTF_8)
}.getOrNull()

class MessageStore {
  private val consumed = Caching.of<String, ConsumedMessage>()
  private val published = Caching.of<String, PublishedMessage>()
  private val committed = Caching.of<String, CommittedMessage>()
  private val retried = Caching.of<String, ConsumedMessage>()
  private val failedMessages = Caching.of<String, ConsumedMessage>()
  private val acknowledged = Caching.of<String, AcknowledgedMessage>()

  private val mutableVersion = MutableStateFlow(0L)
  private val mutableEvents = MutableSharedFlow<StoveMessageEvent>(
    extraBufferCapacity = EVENT_BUFFER_CAPACITY,
    onBufferOverflow = BufferOverflow.DROP_OLDEST
  )

  /**
   * Monotonically increasing counter, bumped on every recorded message.
   * Because it is a [StateFlow], `version.first { storeCondition() }` evaluates the condition
   * immediately and again after observed version changes. Rapid updates may be conflated, but
   * each evaluation reads the latest store state, so waiting is signal-driven without polling.
   */
  val version: StateFlow<Long> = mutableVersion.asStateFlow()

  /**
   * Live stream of everything the store records. Dropped events only affect slow collectors;
   * the store itself remains the source of truth for queries.
   */
  val events: SharedFlow<StoveMessageEvent> = mutableEvents.asSharedFlow()

  /**
   * Streams every consumed record exactly once: replays what the store already holds,
   * then continues with live records as they arrive. The flow never completes; bound it
   * with [first], `take`, or a timeout.
   */
  fun consumedRecords(): Flow<ConsumedMessage> = replayThenLive({ consumedMessages() }) { it.id }

  /** Streams every published record exactly once. See [consumedRecords] for semantics. */
  fun publishedRecords(): Flow<PublishedMessage> = replayThenLive({ publishedMessages() }) { it.id }

  /** Streams every committed record exactly once. See [consumedRecords] for semantics. */
  fun committedRecords(): Flow<CommittedMessage> = replayThenLive({ committedMessages() }) { it.id }

  /** Streams every retried record exactly once. See [consumedRecords] for semantics. */
  fun retriedRecords(): Flow<ConsumedMessage> = replayThenLive({ retriedMessages() }) { it.id }

  /** Streams every failed record exactly once. See [consumedRecords] for semantics. */
  fun failedRecords(): Flow<ConsumedMessage> = replayThenLive({ failedMessages() }) { it.id }

  /**
   * Replay-then-live without a subscribe race: collecting [version] (a StateFlow) yields the
   * current value immediately, so records stored before subscription are re-queried and emitted
   * first, and each store change triggers a re-query. Dedup by id keeps every record single-shot.
   */
  private fun <T> replayThenLive(
    query: () -> Collection<T>,
    id: (T) -> String
  ): Flow<T> = flow {
    val seen = HashSet<String>()
    version.collect {
      query().forEach { record -> if (seen.add(id(record))) emit(record) }
    }
  }

  internal fun record(message: ConsumedMessage) {
    consumed.put(message.id, message)
    signal(StoveMessageEvent.Consumed(message))
  }

  internal fun record(message: PublishedMessage) {
    published.put(message.id, message)
    signal(StoveMessageEvent.Published(message))
  }

  internal fun record(message: CommittedMessage) {
    committed.put(message.id, message)
    signal(StoveMessageEvent.Committed(message))
  }

  internal fun record(message: AcknowledgedMessage) {
    acknowledged.put(message.id, message)
    signal(StoveMessageEvent.Acknowledged(message))
  }

  internal fun recordRetry(message: ConsumedMessage) {
    retried.put(message.id, message)
    signal(StoveMessageEvent.Retried(message))
  }

  internal fun recordFailure(message: ConsumedMessage) {
    failedMessages.put(message.id, message)
    signal(StoveMessageEvent.Failed(message))
  }

  private fun signal(event: StoveMessageEvent) {
    mutableEvents.tryEmit(event)
    mutableVersion.update { it + 1 }
  }

  fun failedMessages(): Collection<ConsumedMessage> = failedMessages.asMap().values

  fun consumedMessages(): Collection<ConsumedMessage> = consumed.asMap().values

  fun publishedMessages(): Collection<PublishedMessage> = published.asMap().values

  fun committedMessages(): Collection<CommittedMessage> = committed.asMap().values

  fun retriedMessages(): Collection<ConsumedMessage> = retried.asMap().values

  internal fun isCommitted(
    topic: String,
    offset: Long,
    partition: Int
  ): Boolean = committedMessages()
    .filter { it.topic == topic && it.partition == partition }
    .any { committed -> committed.offset > offset }

  /**
   * Renders the store contents scoped to the given test id.
   *
   * Messages tagged with a different test id are hidden and summarized as a count, so a
   * failing test dumps its own activity instead of everything observed since the suite started.
   * Passing null renders everything (no scoping information available).
   */
  fun dump(testId: String?): String {
    if (testId == null) return toString()

    val scopedConsumed = consumedMessages().filter { it.headers.belongsToTest(testId) }
    val scopedPublished = publishedMessages().filter { it.headers.belongsToTest(testId) }
    val scopedRetried = retriedMessages().filter { it.headers.belongsToTest(testId) }
    val scopedFailed = failedMessages().filter { it.headers.belongsToTest(testId) }
    val topicPartitions = scopedConsumed.map { it.topic to it.partition }.toSet()
    val scopedCommitted = committedMessages().filter { (it.topic to it.partition) in topicPartitions }

    val hidden = (consumedMessages().size - scopedConsumed.size) +
      (publishedMessages().size - scopedPublished.size) +
      (retriedMessages().size - scopedRetried.size) +
      (failedMessages().size - scopedFailed.size)

    val hiddenNote = if (hidden > 0) "\n|($hidden message(s) from other tests hidden)" else ""
    return """
      |Consumed: ${pprint(scopedConsumed)}
      |Published: ${pprint(scopedPublished)}
      |Committed: ${pprint(scopedCommitted)}
      |Retried: ${pprint(scopedRetried)}
      |Failed: ${pprint(scopedFailed)}$hiddenNote
    """.trimIndent().trimMargin()
  }

  override fun toString(): String = """
    |Consumed: ${pprint(consumedMessages())}
    |Published: ${pprint(publishedMessages())}
    |Committed: ${pprint(committedMessages())}
    |Retried: ${pprint(retriedMessages())}
    |Failed: ${pprint(failedMessages())}
  """.trimIndent().trimMargin()

  companion object {
    private const val EVENT_BUFFER_CAPACITY = 4096
  }
}
