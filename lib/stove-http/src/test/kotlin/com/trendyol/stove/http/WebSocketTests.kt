package com.trendyol.stove.http

import arrow.core.some
import com.trendyol.stove.system.*
import com.trendyol.stove.system.Stove
import com.trendyol.stove.system.abstractions.ApplicationUnderTest
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.*
import io.kotest.matchers.collections.shouldHaveSize
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.time.Duration.Companion.seconds

private const val WS_PORT = 9877

/**
 * Application under test that runs a simple WebSocket echo server.
 */
class WebSocketEchoServer : ApplicationUnderTest<Unit> {
  private lateinit var server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>

  override suspend fun start(configurations: List<String>) {
    server = embeddedServer(Netty, port = WS_PORT) {
      install(WebSockets) {
        pingPeriodMillis = 15_000
        timeoutMillis = 15_000
      }
      routing {
        // Echo endpoint - echoes back whatever is sent
        webSocket("/echo") {
          for (frame in incoming) {
            when (frame) {
              is Frame.Text -> {
                val text = frame.readText()
                send(Frame.Text("Echo: $text"))
              }

              is Frame.Binary -> {
                val bytes = frame.readBytes()
                send(Frame.Binary(true, bytes))
              }

              else -> {}
            }
          }
        }

        // Broadcast endpoint - sends multiple messages
        webSocket("/broadcast") {
          for (i in 1..5) {
            send(Frame.Text("Message $i"))
            delay(10)
          }
          close(CloseReason(CloseReason.Codes.NORMAL, "Broadcast complete"))
        }

        // Auth endpoint - checks for authorization header
        webSocket("/secure") {
          val token = call.request.headers["Authorization"]
          if (token != null && token.startsWith("Bearer ")) {
            send(Frame.Text("Authenticated: ${token.substringAfter("Bearer ")}"))
          } else {
            send(Frame.Text("Unauthorized"))
          }
          close(CloseReason(CloseReason.Codes.NORMAL, "Auth check complete"))
        }

        // Binary endpoint - echoes binary data
        webSocket("/binary") {
          for (frame in incoming) {
            when (frame) {
              is Frame.Binary -> {
                val bytes = frame.readBytes()
                send(Frame.Binary(true, bytes.reversedArray()))
              }

              else -> {}
            }
          }
        }
      }
    }
    server.start(wait = false)
    delay(500)
  }

  override suspend fun stop() {
    server.stop(1000, 2000)
  }
}

class WebSocketTests :
  FunSpec({
    lateinit var wsStove: Stove

    beforeSpec {
      wsStove = Stove()
        .with {
          httpClient {
            HttpClientSystemOptions(
              baseUrl = "http://localhost:$WS_PORT"
            )
          }
          applicationUnderTest(WebSocketEchoServer())
        }
      wsStove.run()
    }

    afterSpec {
      wsStove.close()
    }

    test("should send and receive text messages via WebSocket") {
      stove {
        http {
          webSocket("/echo") {
            send("Hello, WebSocket!")
            val response = receiveText()
            response shouldBe "Echo: Hello, WebSocket!"
          }
        }
      }
    }

    test("should send and receive multiple messages") {
      stove {
        http {
          webSocket("/echo") {
            send("First")
            receiveText() shouldBe "Echo: First"

            send("Second")
            receiveText() shouldBe "Echo: Second"

            send("Third")
            receiveText() shouldBe "Echo: Third"
          }
        }
      }
    }

    test("should collect multiple messages from broadcast endpoint") {
      stove {
        http {
          webSocket("/broadcast") {
            val messages = collectTexts(count = 5, timeout = 10.seconds)
            messages shouldHaveSize 5
            messages[0] shouldBe "Message 1"
            messages[4] shouldBe "Message 5"
          }
        }
      }
    }

    test("should handle authentication via headers") {
      stove {
        http {
          webSocket(
            uri = "/secure",
            token = "my-secret-token".some()
          ) {
            val response = receiveText()
            response shouldBe "Authenticated: my-secret-token"
          }
        }
      }
    }

    test("should handle custom headers") {
      stove {
        http {
          webSocket(
            uri = "/secure",
            headers = mapOf("Authorization" to "Bearer custom-token")
          ) {
            val response = receiveText()
            response shouldBe "Authenticated: custom-token"
          }
        }
      }
    }

    test("should send and receive binary data") {
      stove {
        http {
          webSocket("/binary") {
            val data = byteArrayOf(1, 2, 3, 4, 5)
            send(data)

            val response = receiveBinary()
            response shouldBe byteArrayOf(5, 4, 3, 2, 1)
          }
        }
      }
    }

    test("should use StoveWebSocketMessage sealed class") {
      stove {
        http {
          webSocket("/echo") {
            send(StoveWebSocketMessage.Text("Using sealed class"))
            val response = receive()
            response shouldBe StoveWebSocketMessage.Text("Echo: Using sealed class")
          }
        }
      }
    }

    test("should use incoming flow for streaming messages") {
      stove {
        http {
          webSocket("/broadcast") {
            val messages = incomingTexts()
              .take(3)
              .toList()

            messages shouldHaveSize 3
            messages[0] shouldBe "Message 1"
            messages[1] shouldBe "Message 2"
            messages[2] shouldBe "Message 3"
          }
        }
      }
    }

    test("should receive text with timeout") {
      stove {
        http {
          webSocket("/echo") {
            send("Quick message")
            val response = receiveTextWithTimeout(5.seconds)
            response.isSome() shouldBe true
            response.getOrNull() shouldBe "Echo: Quick message"
          }
        }
      }
    }

    test("webSocketExpect should work as alias") {
      stove {
        http {
          webSocketExpect("/echo") {
            send("Test")
            receiveText() shouldBe "Echo: Test"
          }
        }
      }
    }

    test("webSocketRaw should provide access to underlying session") {
      stove {
        http {
          webSocketRaw("/echo") {
            send(Frame.Text("Raw frame"))
            val frame = incoming.receive()
            (frame as Frame.Text).readText() shouldBe "Echo: Raw frame"
          }
        }
      }
    }

    test("should properly close connection") {
      stove {
        http {
          webSocket("/echo") {
            send("Before close")
            receiveText() shouldBe "Echo: Before close"
            close("Test completed")
          }
        }
      }
    }

    test("should access underlying session for advanced operations") {
      stove {
        http {
          webSocket("/echo") {
            underlyingSession {
              send(Frame.Text("Advanced"))
              val frame = incoming.receive()
              (frame as Frame.Text).readText() shouldBe "Echo: Advanced"
            }
          }
        }
      }
    }
  })

class WebSocketUrlBuildingTests :
  FunSpec({
    test("should convert http to ws") {
      val testSystem = Stove()
      testSystem.with {
        httpClient {
          HttpClientSystemOptions(baseUrl = "http://localhost:8080")
        }
      }
      val httpSystem = testSystem.getOrNone<HttpSystem>().getOrNull()!!
      httpSystem.buildWebSocketUrl("/chat") shouldBe "ws://localhost:8080/chat"
    }

    test("should convert https to wss") {
      val testSystem = Stove()
      testSystem.with {
        httpClient {
          HttpClientSystemOptions(baseUrl = "https://localhost:8080")
        }
      }
      val httpSystem = testSystem.getOrNone<HttpSystem>().getOrNull()!!
      httpSystem.buildWebSocketUrl("/chat") shouldBe "wss://localhost:8080/chat"
    }

    test("should handle uri without leading slash") {
      val testSystem = Stove()
      testSystem.with {
        httpClient {
          HttpClientSystemOptions(baseUrl = "http://localhost:8080")
        }
      }
      val httpSystem = testSystem.getOrNone<HttpSystem>().getOrNull()!!
      httpSystem.buildWebSocketUrl("chat") shouldBe "ws://localhost:8080/chat"
    }

    test("should handle baseUrl without protocol") {
      val testSystem = Stove()
      testSystem.with {
        httpClient {
          HttpClientSystemOptions(baseUrl = "localhost:8080")
        }
      }
      val httpSystem = testSystem.getOrNone<HttpSystem>().getOrNull()!!
      httpSystem.buildWebSocketUrl("/chat") shouldBe "ws://localhost:8080/chat"
    }
  })
