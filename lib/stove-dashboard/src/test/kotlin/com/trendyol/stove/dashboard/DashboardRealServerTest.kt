package com.trendyol.stove.dashboard

import com.google.protobuf.Timestamp
import com.trendyol.stove.dashboard.api.DashboardEvent
import com.trendyol.stove.dashboard.api.EntryRecordedEvent
import com.trendyol.stove.dashboard.api.RunEndedEvent
import com.trendyol.stove.dashboard.api.RunStartedEvent
import com.trendyol.stove.dashboard.api.TestEndedEvent
import com.trendyol.stove.dashboard.api.TestStartedEvent
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/** Opt in with STOVE_REAL_SERVER_BINARY pointing to the compiled Rust server. */
class DashboardRealServerTest : FunSpec({
  val binary = System.getenv("STOVE_REAL_SERVER_BINARY")
  if (binary != null) {
    test("ten Kotlin producers deliver their full evidence to the actual Rust server") {
      val directory = Files.createTempDirectory("stove-real-producer")
      val httpPort = ServerSocket(0).use { it.localPort }
      val grpcPort = ServerSocket(0).use { it.localPort }
      val server = ProcessBuilder(
        binary, "--db", directory.resolve("server.db").toString(),
        "--port", httpPort.toString(), "--grpc-port", grpcPort.toString(),
        "--retention-runs-per-app", "0", "--no-skills-check"
      ).redirectErrorStream(true).redirectOutput(directory.resolve("server.log").toFile()).start()
      val base = "http://127.0.0.1:$httpPort/api/v1"
      val client = HttpClient.newHttpClient()
      fun get(path: String): String = client.send(
        HttpRequest.newBuilder(URI.create(base + path)).GET().build(),
        HttpResponse.BodyHandlers.ofString()
      ).let { response ->
        response.statusCode() shouldBe 200
        response.body()
      }
      try {
        withTimeout(15000) {
          while (runCatching { get("/meta") }.isFailure) {
            check(server.isAlive) { Files.readString(directory.resolve("server.log")) }
            delay(50)
          }
        }
        val count = System.getenv("STOVE_REAL_SERVER_EVENTS")?.toInt() ?: 100
        val endpoint = DashboardIngestion.Http("http://127.0.0.1:$httpPort")
        val options = DashboardSpoolOptions(directory.resolve("spool"))
        coroutineScope {
          (1..10).map { producer ->
            async(Dispatchers.IO) {
              val emitter = DashboardEmitter(endpoint, spoolOptions = options)
              val run = "producer-$producer"
              val timestamp = Timestamp.newBuilder().setSeconds(1_704_067_200).build()
              fun event() = DashboardEvent.newBuilder().setRunId(run)
              emitter.tryEmit(event().setRunStarted(RunStartedEvent.newBuilder().setAppName("real-producer").setTimestamp(timestamp)).build())
              emitter.tryEmit(event().setTestStarted(TestStartedEvent.newBuilder().setTestId("test").setTestName("test").setTimestamp(timestamp)).build())
              repeat(count) { index ->
                emitter.tryEmit(
                  event().setEntryRecorded(
                    EntryRecordedEvent.newBuilder()
                      .setTestId("test").setSystem("HTTP").setAction("assert-$index").setResult("PASSED").setTimestamp(timestamp)
                  ).build()
                )
              }
              emitter.tryEmit(event().setTestEnded(TestEndedEvent.newBuilder().setTestId("test").setStatus("PASSED").setTimestamp(timestamp)).build())
              emitter.tryEmit(event().setRunEnded(RunEndedEvent.newBuilder().setTotalTests(1).setPassed(1).setTimestamp(timestamp)).build())
              emitter.close()
            }
          }.awaitAll()
        }
        DashboardSpool(endpoint, options).use { it.status().pendingEvents shouldBe 0 }
        repeat(10) { producer ->
          val entries = Json.parseToJsonElement(get("/runs/producer-${producer + 1}/tests/test/entries")) as JsonArray
          entries.size shouldBe count
          entries.forEach { it.jsonObject.getValue("attempt_count").jsonPrimitive.content shouldBe "1" }
        }
      } finally {
        server.destroy()
        if (!server.waitFor(5, TimeUnit.SECONDS)) server.destroyForcibly()
      }
    }
  }
})
