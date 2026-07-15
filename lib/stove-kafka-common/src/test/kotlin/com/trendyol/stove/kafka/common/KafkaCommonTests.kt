package com.trendyol.stove.kafka.common

import com.trendyol.stove.messaging.*
import com.trendyol.stove.reporting.StoveReporter
import com.trendyol.stove.serialization.StoveSerde
import com.trendyol.stove.tracing.TraceContext
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private data class CommonEvent(val name: String)

private val serde = StoveSerde.jackson.anyByteArraySerde()

private fun record(
  topic: String = "topic",
  partition: Int? = 0,
  offset: Long? = 0,
  headers: Map<String, Any> = emptyMap(),
  event: CommonEvent = CommonEvent("event"),
  reason: Throwable? = null
) = DefaultKafkaRecord(
  id = UUID.randomUUID().toString(),
  value = serde.serialize(event),
  metadata = MessageMetadata(topic, "key", headers),
  partition = partition,
  offset = offset,
  reason = reason
)

class KafkaCommonTests :
  FunSpec({
    test("store signals changes and replay flows emit each record once") {
      val store = KafkaMessageStore<DefaultKafkaRecord>()
      val first = record(event = CommonEvent("first"))
      val second = record(offset = 1, event = CommonEvent("second"))
      store.recordConsumed(first)

      val collected = mutableListOf<DefaultKafkaRecord>()
      val collector = launch(start = CoroutineStart.UNDISPATCHED) {
        store.consumedRecords().take(2).toList(collected)
      }

      store.recordPublished(record(event = CommonEvent("unrelated")))
      store.recordConsumed(second)
      collector.join()

      store.version.value shouldBe 3L
      collected shouldContainExactly listOf(first, second)
    }

    test("test scoping supports explicit headers and percent-decoded baggage") {
      emptyMap<String, Any>().belongsToTest("test-1") shouldBe true
      mapOf("X-STOVE-TEST-ID" to "test-2").belongsToTest("test-1") shouldBe false
      mapOf("baggage" to "region=eu,stove.test.id=my%20test;prop=1").stoveTestId() shouldBe "my test"
      mapOf("baggage" to "stove.test.id=a+b").stoveTestId() shouldBe "a+b"
      mapOf("baggage" to "stove.test.id=%ZZ").belongsToTest("test-1") shouldBe true
    }

    test("commit-aware assertions wait for a matching commit") {
      val store = KafkaMessageStore<DefaultKafkaRecord>()
      val assertions = KafkaAssertions(store, serde, requireConsumedCommit = true)
      store.recordConsumed(record(offset = 3, event = CommonEvent("committed")))

      launch {
        delay(50)
        store.recordCommitted(
          DefaultKafkaRecord(
            id = UUID.randomUUID().toString(),
            value = byteArrayOf(),
            metadata = MessageMetadata("topic", "", emptyMap()),
            partition = 0,
            offset = 4
          )
        )
      }

      assertions.waitUntilConsumed(1.seconds, CommonEvent::class) {
        it.message.isSome { event -> event.name == "committed" }
      }
    }

    test("a matching failure terminates a consumed assertion immediately") {
      val store = KafkaMessageStore<DefaultKafkaRecord>()
      val assertions = KafkaAssertions(store, serde)
      store.recordFailed(record(event = CommonEvent("failed"), reason = IllegalStateException("boom")))

      val failure = shouldThrow<AssertionError> {
        withTimeout(500.milliseconds) {
          assertions.waitUntilConsumed(30.seconds, CommonEvent::class) {
            it.message.isSome { event -> event.name == "failed" }
          }
        }
      }

      failure.message shouldContain "but failed"
    }

    test("Spring failure semantics reject a matching success after observing the failure") {
      val store = KafkaMessageStore<DefaultKafkaRecord>()
      val assertions = KafkaAssertions(
        store,
        serde,
        failIfConsumedWhileWaitingForFailure = true
      )
      store.recordFailed(record(event = CommonEvent("shared"), reason = IllegalStateException("boom")))
      store.recordConsumed(record(event = CommonEvent("shared")))

      val failure = shouldThrow<AssertionError> {
        withTimeout(500.milliseconds) {
          assertions.waitUntilFailed(30.seconds, CommonEvent::class) { parsed ->
            val failed = parsed as FailedParsedMessage<CommonEvent>
            failed.reason.message == "boom" &&
              failed.message.isSome { event -> event.name == "shared" }
          }
        }
      }

      failure.message shouldContain "consumed successfully"
    }

    test("failed assertions expose the transport failure reason") {
      val store = KafkaMessageStore<DefaultKafkaRecord>()
      val assertions = KafkaAssertions(store, serde)
      store.recordFailed(record(event = CommonEvent("failed"), reason = IllegalArgumentException("reason")))

      assertions.waitUntilFailed(1.seconds, CommonEvent::class) { parsed ->
        parsed is FailedParsedMessage && parsed.reason.message == "reason"
      }
    }

    test("assertion reporting preserves cancellation without recording a failure") {
      val reporter = StoveReporter()

      shouldThrow<CancellationException> {
        runKafkaAssertion<CommonEvent>(
          reporter = reporter,
          systemName = "Kafka",
          assertionName = "shouldBeConsumed",
          typeName = "CommonEvent",
          timeout = 1.seconds,
          expected = "a message"
        ) { throw CancellationException("cancelled") }
      }

      reporter.currentTestOrNull() shouldBe null
    }

    test("dump hides records tagged for another test") {
      val store = KafkaMessageStore<DefaultKafkaRecord>()
      store.recordConsumed(
        record(
          headers = mapOf(TraceContext.STOVE_TEST_ID_HEADER to "test-1"),
          event = CommonEvent("mine")
        )
      )
      store.recordConsumed(
        record(
          headers = mapOf(TraceContext.STOVE_TEST_ID_HEADER to "test-2"),
          event = CommonEvent("theirs")
        )
      )

      val dump = store.dump("test-1")
      dump shouldContain "mine"
      dump shouldContain "1 message(s) from other tests hidden"
    }
  })
