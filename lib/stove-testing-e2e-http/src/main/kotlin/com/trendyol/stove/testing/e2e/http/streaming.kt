package com.trendyol.stove.testing.e2e.http

import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.util.*
import io.ktor.utils.io.*
import kotlinx.coroutines.flow.*

@OptIn(InternalAPI::class)
@Suppress("unused")
suspend fun <T> HttpStatement.readJsonStream(transform: (line: String) -> T): Flow<T> = flow {
  execute {
    check(it.status.isSuccess()) { "Request failed with status: ${it.status}" }
    while (!it.content.isClosedForRead) {
      it.content.readUTF8Line()?.let { line ->
        emit(transform(line))
      }
    }
  }
}
