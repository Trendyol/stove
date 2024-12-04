package com.trendyol.stove.examples.kotlin.spring.e2e.tests

import com.trendyol.stove.examples.kotlin.spring.ExampleData
import com.trendyol.stove.testing.e2e.http.http
import com.trendyol.stove.testing.e2e.serialization.StoveSerde
import com.trendyol.stove.testing.e2e.serialization.StoveSerde.Companion.deserialize
import com.trendyol.stove.testing.e2e.system.TestSystem.Companion.validate
import io.kotest.core.spec.style.FunSpec
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.jackson.*
import io.ktor.util.*
import io.ktor.utils.io.*
import kotlinx.coroutines.flow.*
import java.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

class StreamingTests :
  FunSpec({
    test("streaming") {
      validate {
        http {
          streamClient()
            .prepareGet {
              url("http://localhost:8024/api/streaming/json")
              parameter("load", 100)
              parameter("delay", 1)
              contentType(ContentType.parse("application/x-ndjson"))
            }.also { response ->
              response
                .readJsonStream { line ->
                  StoveSerde.jackson.anyJsonStringSerde().deserialize<ExampleData>(line)
                }.collect { data ->
                  println(data)
                }
            }
        }
      }
    }
  })

@OptIn(InternalAPI::class)
suspend fun <T> HttpStatement.readJsonStream(transform: (String) -> T): Flow<T> = flow {
  execute {
    while (!it.content.isClosedForRead) {
      val line = it.content.readUTF8Line()
      if (line != null) {
        emit(transform(line))
      }
    }
  }
}

private fun streamClient(timeout: Duration = 30.seconds.toJavaDuration()): HttpClient {
  val client = HttpClient(OkHttp) {
    engine {
      config {
        followRedirects(true)
        callTimeout(timeout)
        connectTimeout(timeout)
        readTimeout(timeout)
        writeTimeout(timeout)
      }
    }
    install(ContentNegotiation) {
      register(ContentType.Application.Json, JacksonConverter())
      register(ContentType.Application.ProblemJson, JacksonConverter())
      register(ContentType.parse("application/x-ndjson"), JacksonConverter())
    }
  }
  return client
}
