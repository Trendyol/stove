package com.trendyol.stove.testing.e2e.http

import com.trendyol.stove.testing.e2e.serialization.StoveSerde
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.util.*
import io.ktor.utils.io.*
import kotlinx.coroutines.flow.*

@OptIn(InternalAPI::class)
@Suppress("unused")
fun <T> HttpStatement.readJsonTextStream(transform: suspend (line: String) -> T): Flow<T> = flow {
  execute {
    check(it.status.isSuccess()) { "Request failed with status: ${it.status}" }
    while (!it.content.isClosedForRead) {
      it.content.readUTF8Line()?.let { line ->
        if (line.isBlank()) return@let
        emit(transform(line))
      }
    }
  }
}

@OptIn(InternalAPI::class)
@Suppress("unused")
fun <T> HttpStatement.readJsonContentStream(transform: suspend (line: ByteReadChannel) -> T): Flow<T> = flow {
  execute {
    check(it.status.isSuccess()) { "Request failed with status: ${it.status}" }
    while (!it.content.isClosedForRead) {
      it.content.readUTF8Line()?.let { line ->
        if (line.isBlank()) return@let
        emit(transform(ByteReadChannel(line.toByteArray())))
      }
    }
  }
}

/**
 * Serializes the items to a stream of JSON strings.
 */
fun <T : Any> StoveSerde<T, ByteArray>.serializeToStreamJson(items: List<T>): ByteArray = items
  .joinToString("\n") { String(serialize(it)) }
  .toByteArray()
