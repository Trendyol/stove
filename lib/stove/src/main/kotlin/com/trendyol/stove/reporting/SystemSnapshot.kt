package com.trendyol.stove.reporting

import java.lang.reflect.Array as JvmArray

/**
 * Snapshot of a system's state at a point in time.
 * Used for debugging test failures by providing context about what the system was doing.
 *
 * Provide immutable or detached custom objects as leaves. Stove copies nested maps, lists, sets and arrays for events.
 *
 * Systems with rich internal state (like Kafka's MessageStore or WireMock's stubs)
 * should override [Reports.snapshot] to provide detailed state information.
 */
data class SystemSnapshot(
  val system: String,
  val state: Map<String, Any>,
  val summary: String
)

internal fun SystemSnapshot.detached() = copy(state = state.mapValues { (_, value) -> value.detachedValue() })

private fun Any.detachedValue(): Any = when (this) {
  is Map<*, *> -> entries.associate { (key, value) -> key?.detachedValue() to value?.detachedValue() }
  is List<*> -> map { it?.detachedValue() }
  is Set<*> -> mapTo(linkedSetOf()) { it?.detachedValue() }
  is java.util.Date -> clone()
  else -> if (javaClass.isArray) detachedArray() else this
}

private fun Any.detachedArray(): Any {
  val component = javaClass.componentType
  val size = JvmArray.getLength(this)
  val copy = JvmArray.newInstance(component, size)
  if (component.isPrimitive) {
    System.arraycopy(this, 0, copy, 0, size)
  } else {
    repeat(size) { JvmArray.set(copy, it, JvmArray.get(this, it)?.detachedValue()) }
  }
  return copy
}
