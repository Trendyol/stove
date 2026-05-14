package com.trendyol.stove.logging

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.ThrowableProxyUtil
import ch.qos.logback.core.AppenderBase
import org.slf4j.LoggerFactory
import java.time.Instant

internal object LogbackInstaller {
  private const val APPENDER_NAME = "STOVE_LOG_CAPTURE"

  fun install(sink: StoveLogSink) {
    if (!isAvailable()) return
    val context = LoggerFactory.getILoggerFactory() as? LoggerContext ?: return
    val root = context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)
    if (root.getAppender(APPENDER_NAME) != null) return
    val appender = StoveLogbackAppender(sink).apply {
      name = APPENDER_NAME
      this.context = context
      start()
    }
    root.addAppender(appender)
  }

  fun uninstall() {
    if (!isAvailable()) return
    val context = LoggerFactory.getILoggerFactory() as? LoggerContext ?: return
    val root = context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)
    val appender = root.getAppender(APPENDER_NAME)
    root.detachAppender(APPENDER_NAME)
    appender?.stop()
  }

  private fun isAvailable(): Boolean =
    runCatching { Class.forName("ch.qos.logback.classic.LoggerContext") }.isSuccess
}

private class StoveLogbackAppender(
  private val sink: StoveLogSink
) : AppenderBase<ILoggingEvent>() {
  override fun append(eventObject: ILoggingEvent) {
    val throwable = eventObject.throwableProxy
    sink.capture(
      CapturedLog(
        timestamp = Instant.ofEpochMilli(eventObject.timeStamp),
        source = "logback",
        level = eventObject.level.toStoveLevel(),
        logger = eventObject.loggerName ?: "",
        thread = eventObject.threadName ?: Thread.currentThread().name,
        message = eventObject.formattedMessage ?: "",
        throwableType = throwable?.className,
        throwableMessage = throwable?.message,
        throwableStackTrace = throwable?.let(ThrowableProxyUtil::asString),
        mdc = eventObject.mdcPropertyMap.orEmpty()
      )
    )
  }

  private fun Level.toStoveLevel(): StoveLogLevel = when {
    isGreaterOrEqual(Level.ERROR) -> StoveLogLevel.ERROR
    isGreaterOrEqual(Level.WARN) -> StoveLogLevel.WARN
    isGreaterOrEqual(Level.INFO) -> StoveLogLevel.INFO
    isGreaterOrEqual(Level.DEBUG) -> StoveLogLevel.DEBUG
    else -> StoveLogLevel.TRACE
  }
}
