package com.trendyol.stove.logging

import com.trendyol.stove.reporting.StoveMdc
import com.trendyol.stove.reporting.StoveTestContext
import com.trendyol.stove.reporting.StoveTestContextHolder
import com.trendyol.stove.system.Stove
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds

class LoggingSystemTest : FunSpec({
  test("logback appender captures MDC, throwable, redaction, and prevents duplicate install") {
    val listener = RecordingLogListener()
    val system = LoggingSystem(
      Stove(),
      LoggingSystemOptions(excludeLoggerPatterns = emptyList())
    )
    system.addLogListener(listener)

    try {
      system.run()
      LogbackInstaller.install(system)

      MDC.put(StoveMdc.TEST_ID_KEY, "test-logback")
      MDC.put("password", "plain-secret")
      LoggerFactory.getLogger("app.test.LogbackCapture")
        .warn("token=abc123 visible", IllegalStateException("boom"))

      val records = listener.awaitRecords(1)

      records shouldHaveSize 1
      with(records.single()) {
        testId shouldBe "test-logback"
        severityText shouldBe "WARN"
        logger shouldBe "app.test.LogbackCapture"
        body shouldContain "token=[REDACTED]"
        body shouldNotContain "abc123"
        attributes["password"] shouldBe "[REDACTED]"
        exceptionType shouldBe IllegalStateException::class.java.name
        exceptionMessage shouldBe "boom"
      }
    } finally {
      MDC.clear()
      system.close()
    }
  }

  test("log4j2 appender captures direct log4j events") {
    val listener = RecordingLogListener()
    val system = LoggingSystem(
      Stove(),
      LoggingSystemOptions(excludeLoggerPatterns = emptyList())
    )
    system.addLogListener(listener)

    system.use { system ->
      system.run()

      val logger = org.apache.logging.log4j.LogManager.getLogger("app.test.Log4jCapture")
      logger.error("log4j2 message")

      val record = listener.awaitRecords(1).single()

      record.source shouldBe "log4j2"
      record.severityText shouldBe "ERROR"
      record.logger shouldBe "app.test.Log4jCapture"
      record.body shouldBe "log4j2 message"
    }
  }

  test("direct capture filters, correlates from Stove context, truncates, and enriches failure report") {
    val listener = RecordingLogListener()
    val system = LoggingSystem(
      Stove(),
      LoggingSystemOptions(
        minLevel = StoveLogLevel.WARN,
        maxMessageLength = 32,
        excludeLoggerPatterns = emptyList()
      )
    )
    system.addLogListener(listener)
    StoveTestContextHolder.set(StoveTestContext("test-direct", "direct test", "Spec"))

    try {
      system.run()
      system.capture(captured(level = StoveLogLevel.INFO, message = "ignored"))
      system.capture(captured(level = StoveLogLevel.WARN, message = "secret=abcdef1234567890 and a long tail"))

      val record = listener.awaitRecords(1).single()

      record.testId shouldBe "test-direct"
      record.correlationSource shouldBe LogCorrelationSource.STOVE_TEST_CONTEXT
      record.truncated shouldBe true
      record.body shouldContain "[REDACTED]"
      record.body shouldNotContain "abcdef1234567890"
      system.contribute("test-direct") shouldContain "LOGS (WARN+)"
    } finally {
      StoveTestContextHolder.clear()
      system.close()
    }
  }

  test("queue overflow emits dropped markers without blocking log callers") {
    val listener = BlockingLogListener()
    val system = LoggingSystem(
      Stove(),
      LoggingSystemOptions(
        queueCapacity = 1,
        excludeLoggerPatterns = emptyList()
      )
    )
    system.addLogListener(listener)

    try {
      system.run()
      system.capture(captured(message = "first"))
      listener.awaitBlocked() shouldBe true

      repeat(25) { index ->
        system.capture(captured(message = "overflow-$index"))
      }

      listener.release()
      val dropped = listener.awaitDropped()

      dropped.reason shouldBe "queue_overflow"
      (dropped.droppedCount > 0) shouldBe true
    } finally {
      listener.release()
      system.close()
    }
  }
})

private class RecordingLogListener : LogEventListener {
  private val records = CopyOnWriteArrayList<StoveLogRecord>()

  override fun onLogRecorded(record: StoveLogRecord) {
    records.add(record)
  }

  suspend fun awaitRecords(count: Int): List<StoveLogRecord> {
    repeat(100) {
      if (records.size >= count) return records.toList()
      delay(25.milliseconds)
    }
    return records.toList()
  }
}

private class BlockingLogListener : LogEventListener {
  private val blocked = CountDownLatch(1)
  private val release = CountDownLatch(1)
  private val dropped = CopyOnWriteArrayList<LogsDropped>()

  override fun onLogRecorded(record: StoveLogRecord) {
    blocked.countDown()
    release.await(5, TimeUnit.SECONDS)
  }

  override fun onLogsDropped(event: LogsDropped) {
    dropped.add(event)
  }

  fun awaitBlocked(): Boolean = blocked.await(5, TimeUnit.SECONDS)

  fun release() {
    release.countDown()
  }

  suspend fun awaitDropped(): LogsDropped {
    repeat(100) {
      dropped.firstOrNull()?.let { return it }
      delay(25.milliseconds)
    }
    error("no dropped log marker was emitted")
  }
}

private fun captured(
  level: StoveLogLevel = StoveLogLevel.WARN,
  message: String = "message"
): CapturedLog = CapturedLog(
  timestamp = Instant.now(),
  source = "test",
  level = level,
  logger = "app.test.DirectCapture",
  thread = Thread.currentThread().name,
  message = message,
  throwableType = null,
  throwableMessage = null,
  throwableStackTrace = null,
  mdc = emptyMap()
)
