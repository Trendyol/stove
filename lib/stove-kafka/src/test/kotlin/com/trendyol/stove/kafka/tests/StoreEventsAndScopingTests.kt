package com.trendyol.stove.kafka.tests

import com.trendyol.stove.kafka.*
import com.trendyol.stove.kafka.intercepting.*
import com.trendyol.stove.serialization.StoveSerde
import com.trendyol.stove.tracing.TraceContext
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okio.ByteString.Companion.EMPTY
import okio.ByteString.Companion.toByteString
import java.util.*
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

internal data class ScopedEvent(val name: String)

private val serde = StoveSerde.jackson.anyByteArraySerde()

private fun consumed(
  topic: String = "topic",
  offset: Long = 0,
  partition: Int = 0,
  headers: Map<String, String> = emptyMap(),
  payload: Any = ScopedEvent("event")
) = ConsumedMessage(
  id = UUID.randomUUID().toString(),
  message = serde.serialize(payload).toByteString(),
  topic = topic,
  partition = partition,
  offset = offset,
  key = "key",
  headers = headers,
  unknownFields = EMPTY
)

private fun published(
  topic: String = "topic",
  headers: Map<String, String> = emptyMap(),
  payload: Any = ScopedEvent("event")
) = PublishedMessage(
  id = UUID.randomUUID().toString(),
  message = serde.serialize(payload).toByteString(),
  topic = topic,
  key = "key",
  headers = headers,
  unknownFields = EMPTY
)

private fun committed(
  topic: String = "topic",
  offset: Long = 1,
  partition: Int = 0
) = CommittedMessage(
  id = UUID.randomUUID().toString(),
  topic = topic,
  partition = partition,
  offset = offset,
  metadata = "",
  unknownFields = EMPTY
)

private fun acknowledged() = AcknowledgedMessage(
  id = UUID.randomUUID().toString(),
  topic = "topic",
  partition = 0,
  offset = 0,
  exception = "",
  unknownFields = EMPTY
)

private fun taggedWith(testId: String): Map<String, String> = mapOf(TraceContext.STOVE_TEST_ID_HEADER to testId)

private class Observer {
  val store = MessageStore()
  val recorder = KafkaRecorder(store, TopicSuffixes())
  val assertions = KafkaAssertions(store, serde, TopicSuffixes())
}

private fun observer() = Observer()

class StoreEventsAndScopingTests :
  FunSpec({

    test("bumps version and emits an event for every recorded message") {
      val store = MessageStore()
      store.version.value shouldBe 0L

      val received = mutableListOf<StoveMessageEvent>()
      val collector = launch(start = CoroutineStart.UNDISPATCHED) {
        store.events.take(3).toList(received)
      }

      store.record(consumed())
      store.record(published())
      store.record(committed())
      collector.join()

      store.version.value shouldBe 3L
      received.filterIsInstance<StoveMessageEvent.Consumed>().size shouldBe 1
      received.filterIsInstance<StoveMessageEvent.Published>().size shouldBe 1
      received.filterIsInstance<StoveMessageEvent.Committed>().size shouldBe 1
    }

    test("record flows deliver live records") {
      val store = MessageStore()

      launch {
        delay(50)
        store.record(consumed(payload = ScopedEvent("live")))
      }

      val record = withTimeout(2.seconds) { store.consumedRecords().first() }
      serde.deserialize(record.message.toByteArray(), ScopedEvent::class.java).name shouldBe "live"
    }

    test("observer preserves the unary JVM and Go bridge contract") {
      val observer = observer()
      val server = StoveKafkaObserverGrpcServer(observer.recorder)

      server.onPublishedMessage(published()).status shouldBe 200
      server.onConsumedMessage(consumed()).status shouldBe 200
      server.onCommittedMessage(committed()).status shouldBe 200
      server.onAcknowledgedMessage(acknowledged()).status shouldBe 200

      observer.store.publishedMessages().size shouldBe 1
      observer.store.consumedMessages().size shouldBe 1
      observer.store.committedMessages().size shouldBe 1
    }

    test("record flows replay records stored before subscription") {
      val store = MessageStore()
      store.record(consumed(payload = ScopedEvent("early")))

      // Already stored, so the flow must emit immediately instead of hanging.
      val record = withTimeout(100.milliseconds) { store.consumedRecords().first() }
      serde.deserialize(record.message.toByteArray(), ScopedEvent::class.java).name shouldBe "early"
    }

    test("record flows emit each record exactly once and are not disturbed by other record types") {
      val store = MessageStore()
      store.record(consumed(offset = 0))

      val collected = mutableListOf<ConsumedMessage>()
      val collector = launch(start = CoroutineStart.UNDISPATCHED) {
        store.consumedRecords().take(2).toList(collected)
      }

      // Bump the version with unrelated record types; consumed must not re-emit.
      store.record(published())
      store.record(committed())
      store.record(consumed(offset = 1))
      collector.join()

      collected.map { it.offset } shouldBe listOf(0L, 1L)
    }

    test("dump scoped to a test id hides other tests' messages and reports the count") {
      val store = MessageStore()
      store.record(consumed(payload = ScopedEvent("mine"), headers = taggedWith("test-1")))
      store.record(consumed(payload = ScopedEvent("theirs"), headers = taggedWith("test-2")))
      store.record(consumed(payload = ScopedEvent("untagged")))

      val dump = store.dump("test-1")
      dump shouldContain "mine"
      dump shouldContain "untagged"
      dump shouldNotContain "theirs"
      dump shouldContain "1 message(s) from other tests hidden"
    }

    test("dump without a test id shows everything") {
      val store = MessageStore()
      store.record(consumed(payload = ScopedEvent("mine"), headers = taggedWith("test-1")))
      store.record(consumed(payload = ScopedEvent("theirs"), headers = taggedWith("test-2")))

      val dump = store.dump(null)
      dump shouldContain "mine"
      dump shouldContain "theirs"
    }

    test("waitUntilConsumed matches messages tagged with the current test id") {
      val observer = observer()
      TraceContext.use("test-1") {
        val message = consumed(offset = 0, headers = taggedWith("test-1"), payload = ScopedEvent("mine"))
        observer.recorder.onMessageConsumed(message)
        observer.recorder.onMessageCommitted(committed(offset = 1))

        observer.assertions.waitUntilConsumed(1.seconds, ScopedEvent::class) { parsed ->
          parsed.message.isSome { it.name == "mine" }
        }
      }
    }

    test("waitUntilConsumed matches untagged messages for apps that do not propagate headers") {
      val observer = observer()
      TraceContext.use("test-1") {
        observer.recorder.onMessageConsumed(consumed(offset = 0, payload = ScopedEvent("untagged")))
        observer.recorder.onMessageCommitted(committed(offset = 1))

        observer.assertions.waitUntilConsumed(1.seconds, ScopedEvent::class) { parsed ->
          parsed.message.isSome { it.name == "untagged" }
        }
      }
    }

    test("waitUntilConsumed ignores messages tagged with another test id") {
      val observer = observer()
      TraceContext.use("test-1") {
        observer.recorder.onMessageConsumed(consumed(offset = 0, headers = taggedWith("test-2"), payload = ScopedEvent("theirs")))
        observer.recorder.onMessageCommitted(committed(offset = 1))

        val failure = shouldThrow<AssertionError> {
          observer.assertions.waitUntilConsumed(250.milliseconds, ScopedEvent::class) { parsed ->
            parsed.message.isSome { it.name == "theirs" }
          }
        }
        failure.message shouldContain "message(s) from other tests hidden"
      }
    }

    test("another test's failed message does not fail the current test") {
      val observer = observer()
      TraceContext.use("test-1") {
        // Same payload failed in another test; only this test's copy succeeded.
        observer.recorder.onMessageConsumed(
          consumed(topic = "topic.DLT", offset = 0, headers = taggedWith("test-2"), payload = ScopedEvent("shared"))
        )
        observer.recorder.onMessageConsumed(consumed(offset = 0, headers = taggedWith("test-1"), payload = ScopedEvent("shared")))
        observer.recorder.onMessageCommitted(committed(offset = 1))

        observer.assertions.waitUntilConsumed(1.seconds, ScopedEvent::class) { parsed ->
          parsed.message.isSome { it.name == "shared" }
        }
      }
    }

    test("waitUntilPublished is scoped to the current test id") {
      val observer = observer()
      TraceContext.use("test-1") {
        observer.recorder.onMessagePublished(published(headers = taggedWith("test-2"), payload = ScopedEvent("theirs")))
        observer.recorder.onMessagePublished(published(headers = taggedWith("test-1"), payload = ScopedEvent("mine")))

        observer.assertions.waitUntilPublished(1.seconds, ScopedEvent::class) { parsed ->
          parsed.message.isSome { it.name == "mine" }
        }

        shouldThrow<AssertionError> {
          observer.assertions.waitUntilPublished(250.milliseconds, ScopedEvent::class) { parsed ->
            parsed.message.isSome { it.name == "theirs" }
          }
        }
      }
    }

    test("untagged headers never exclude a message, regardless of test id") {
      emptyMap<String, String>().belongsToTest("test-1") shouldBe true
      mapOf("some-header" to "value").belongsToTest("test-1") shouldBe true
      emptyMap<String, String>().belongsToTest(null) shouldBe true
    }

    test("test id header is matched case-insensitively") {
      mapOf("x-stove-test-id" to "test-1").belongsToTest("test-1") shouldBe true
      mapOf("X-STOVE-TEST-ID" to "test-2").belongsToTest("test-1") shouldBe false
    }

    test("test id is extracted from OTel baggage on app-published messages") {
      val baggage = mapOf("baggage" to "region=eu,stove.test.id=my%20test%20name;prop=1,other=x")
      baggage.stoveTestId() shouldBe "my test name"
      baggage.belongsToTest("my test name") shouldBe true
      baggage.belongsToTest("another test") shouldBe false
    }

    test("baggage without a stove entry leaves the message untagged") {
      val baggage = mapOf("baggage" to "region=eu,tenant=abc")
      baggage.stoveTestId() shouldBe null
      baggage.belongsToTest("test-1") shouldBe true
    }

    test("malformed baggage leaves the message untagged instead of excluding it") {
      mapOf("baggage" to ",,=broken=,;;").belongsToTest("test-1") shouldBe true
      mapOf("baggage" to "stove.test.id=%ZZ").stoveTestId() shouldBe null
      mapOf("baggage" to "stove.test.id=%ZZ").belongsToTest("test-1") shouldBe true
    }

    test("blank explicit test id falls back to valid baggage") {
      val headers = mapOf(
        "X-Stove-Test-Id" to "",
        "baggage" to "stove.test.id=test-1"
      )
      headers.stoveTestId() shouldBe "test-1"
    }

    test("explicit header wins over baggage") {
      val headers = mapOf(
        "X-Stove-Test-Id" to "test-1",
        "baggage" to "stove.test.id=test-2"
      )
      headers.stoveTestId() shouldBe "test-1"
    }

    test("baggage values with literal plus signs survive decoding") {
      mapOf("baggage" to "stove.test.id=a+b").stoveTestId() shouldBe "a+b"
    }

    test("recording a message without headers does not throw") {
      val observer = observer()
      observer.recorder.onMessageConsumed(consumed(headers = emptyMap()))
      observer.recorder.onMessagePublished(published(headers = emptyMap()))
      observer.store.consumedMessages().size shouldBe 1
      observer.store.publishedMessages().size shouldBe 1
    }
  })
