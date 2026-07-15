package com.trendyol.stove.testing.grpcmock

import com.google.protobuf.Message

/** Reflection-based protobuf parsing for typed matchers and verifications. */
object ProtoPayloads {
  /**
   * Returns a parser for [clazz] backed by its generated static `parseFrom(byte[])`.
   * The returned function yields null for bytes that do not parse as [T].
   */
  fun <T : Message> parserFor(clazz: Class<T>): (ByteArray) -> T? {
    val parseFrom = clazz.getMethod("parseFrom", ByteArray::class.java)
    return { bytes ->
      runCatching {
        @Suppress("UNCHECKED_CAST")
        parseFrom.invoke(null, bytes) as T
      }.getOrNull()
    }
  }

  internal fun Message.singleLine(): String = toString().replace(Regex("\\s+"), " ").trim()
}
