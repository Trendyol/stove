package com.trendyol.stove.testing.e2e.http

import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.util.*
import io.ktor.utils.io.*
import kotlinx.coroutines.flow.*

@Suppress("unused")
suspend fun <T> HttpStatement.readJsonStream(transform: (line: String) -> T): Flow<T> = flow {
  execute { it.readJsonStream(transform) }
}

@OptIn(InternalAPI::class)
@Suppress("unused")
suspend fun <T> HttpResponse.readJsonStream(transform: (line: String) -> T): Flow<T> = flow {
  check(this@readJsonStream.status.isSuccess()) { "Request failed with status: ${this@readJsonStream.status}" }
  while (!this@readJsonStream.content.isClosedForRead) {
    this@readJsonStream.content.readUTF8Line()?.let { line ->
      emit(transform(line))
    }
  }
}
