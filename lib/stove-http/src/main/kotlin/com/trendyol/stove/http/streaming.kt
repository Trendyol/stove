package com.trendyol.stove.http

import arrow.core.toOption
import com.trendyol.stove.serialization.StoveSerde
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
    while (!it.rawContent.isClosedForRead) {
      it.rawContent.readUTF8LineNonEmpty { line -> emit(transform(line)) }
    }
  }
}

@OptIn(InternalAPI::class)
@Suppress("unused")
fun <T> HttpStatement.readJsonContentStream(transform: suspend (line: ByteReadChannel) -> T): Flow<T> = flow {
  execute {
    check(it.status.isSuccess()) { "Request failed with status: ${it.status}" }
    while (!it.rawContent.isClosedForRead) {
      it.rawContent.readUTF8LineNonEmpty { line -> emit(transform(ByteReadChannel(line.toByteArray()))) }
    }
  }
}

private suspend fun ByteReadChannel.readUTF8LineNonEmpty(onRead: suspend (String) -> Unit) {
  readUTF8Line().toOption().filter { it.isNotBlank() }.map { onRead(it) }
}

/**
 * Serializes the items to a stream of JSON strings.
 */
fun <T : Any> StoveSerde<T, ByteArray>.serializeToStreamJson(items: List<T>): ByteArray = items
  .joinToString("\n") { String(serialize(it)) }
  .toByteArray()
