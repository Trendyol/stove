package com.trendyol.stove.messaging.kafka

import com.trendyol.stove.tracing.TraceContext
import java.net.URLDecoder

/** A record is excluded only when its headers prove that it belongs to another test. */
fun Map<String, *>.belongsToTest(testId: String?): Boolean {
  if (testId == null) return true
  return stoveTestId()?.let { it == testId } ?: true
}

/** Extracts Stove's test id from its explicit header or W3C baggage. */
fun Map<String, *>.stoveTestId(): String? {
  entries
    .firstOrNull { it.key.equals(TraceContext.STOVE_TEST_ID_HEADER, ignoreCase = true) }
    ?.value
    ?.headerValue()
    ?.takeIf { it.isNotBlank() }
    ?.let { return it }

  val baggage = entries
    .firstOrNull { it.key.equals(BAGGAGE_HEADER, ignoreCase = true) }
    ?.value
    ?.headerValue()
    ?: return null
  return parseBaggageEntry(baggage, TraceContext.BAGGAGE_TEST_ID_KEY)
}

private fun Any?.headerValue(): String? = when (this) {
  null -> null
  is ByteArray -> toString(Charsets.UTF_8)
  else -> toString()
}

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
  URLDecoder.decode(value.replace("+", "%2B"), Charsets.UTF_8)
}.getOrNull()

private const val BAGGAGE_HEADER = "baggage"
