package com.trendyol.stove.scoping

import com.trendyol.stove.tracing.TraceContext
import java.net.URLDecoder

/** An entry is excluded only when its headers prove that it belongs to another test. */
fun Map<String, *>.belongsToTest(testId: String?): Boolean {
  if (testId == null) return true
  return stoveTestId()?.let { it == testId } ?: true
}

/** Which transport carried the test id. */
enum class TestIdSource { HEADER, BAGGAGE }

/** Extracts Stove's test id from its explicit header or W3C baggage. */
fun Map<String, *>.stoveTestId(): String? = stoveTestIdWithSource()?.first

/** Extracts Stove's test id together with the transport that carried it. */
fun Map<String, *>.stoveTestIdWithSource(): Pair<String, TestIdSource>? {
  entries
    .firstOrNull { it.key.equals(TraceContext.STOVE_TEST_ID_HEADER, ignoreCase = true) }
    ?.value
    ?.headerValue()
    ?.takeIf { it.isNotBlank() }
    ?.let { return it to TestIdSource.HEADER }

  val baggage = entries
    .firstOrNull { it.key.equals(BAGGAGE_HEADER, ignoreCase = true) }
    ?.value
    ?.headerValue()
    ?: return null
  return parseBaggageEntry(baggage, TraceContext.BAGGAGE_TEST_ID_KEY)
    ?.let { it to TestIdSource.BAGGAGE }
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
