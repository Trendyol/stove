package com.trendyol.stove.logging

import org.apache.logging.log4j.Level
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.core.LogEvent
import org.apache.logging.log4j.core.LoggerContext
import org.apache.logging.log4j.core.appender.AbstractAppender
import org.apache.logging.log4j.core.config.Property
import java.time.Instant

internal object Log4j2Installer {
  private const val APPENDER_NAME = "STOVE_LOG_CAPTURE"

  fun install(sink: StoveLogSink) {
    if (!isAvailable()) return
    val context = LogManager.getContext(false) as? LoggerContext ?: return
    val config = context.configuration
    if (config.appenders.containsKey(APPENDER_NAME)) return
    val appender = StoveLog4j2Appender(APPENDER_NAME, sink)
    appender.start()
    config.addAppender(appender)
    config.rootLogger.addAppender(appender, null, null)
    context.updateLoggers()
  }

  fun uninstall() {
    if (!isAvailable()) return
    val context = LogManager.getContext(false) as? LoggerContext ?: return
    val config = context.configuration
    config.rootLogger.removeAppender(APPENDER_NAME)
    config.appenders[APPENDER_NAME]?.stop()
    config.appenders.remove(APPENDER_NAME)
    context.updateLoggers()
  }

  private fun isAvailable(): Boolean =
    runCatching { Class.forName("org.apache.logging.log4j.core.LoggerContext") }.isSuccess
}

private class StoveLog4j2Appender(
  name: String,
  private val sink: StoveLogSink
) : AbstractAppender(name, null, null, true, Property.EMPTY_ARRAY) {
  override fun append(event: LogEvent) {
    val thrown = event.thrown
    sink.capture(
      CapturedLog(
        timestamp = Instant.ofEpochMilli(event.timeMillis),
        source = "log4j2",
        level = event.level.toStoveLevel(),
        logger = event.loggerName ?: "",
        thread = event.threadName ?: Thread.currentThread().name,
        message = event.message?.formattedMessage ?: "",
        throwableType = thrown?.javaClass?.name,
        throwableMessage = thrown?.message,
        throwableStackTrace = thrown?.stackTraceToString(),
        mdc = event.contextData.toMap().mapValues { it.value.toString() }
      )
    )
  }

  private fun Level.toStoveLevel(): StoveLogLevel = when {
    isMoreSpecificThan(Level.ERROR) -> StoveLogLevel.ERROR
    isMoreSpecificThan(Level.WARN) -> StoveLogLevel.WARN
    isMoreSpecificThan(Level.INFO) -> StoveLogLevel.INFO
    isMoreSpecificThan(Level.DEBUG) -> StoveLogLevel.DEBUG
    else -> StoveLogLevel.TRACE
  }
}
