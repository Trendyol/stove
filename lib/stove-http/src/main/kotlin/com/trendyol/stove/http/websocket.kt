@file:Suppress("TooManyFunctions")

package com.trendyol.stove.http

import arrow.core.*
import io.ktor.client.plugins.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*
import kotlinx.coroutines.flow.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Represents a WebSocket message that can be sent or received.
 *
 * @see Text for text messages
 * @see Binary for binary messages
 */
@HttpDsl
sealed class StoveWebSocketMessage {
  /**
   * A text-based WebSocket message.
   *
   * @property content The text content of the message.
   */
  data class Text(
    val content: String
  ) : StoveWebSocketMessage()

  /**
   * A binary WebSocket message.
   *
   * @property content The binary content of the message.
   */
  data class Binary(
    val content: ByteArray
  ) : StoveWebSocketMessage() {
    override fun equals(other: Any?): Boolean {
      if (this === other) return true
      if (javaClass != other?.javaClass) return false
      other as Binary
      return content.contentEquals(other.content)
    }

    override fun hashCode(): Int = content.contentHashCode()
  }
}

/**
 * A test-friendly wrapper around a Ktor WebSocket session.
 *
 * Provides a simplified API for sending and receiving WebSocket messages
 * in e2e tests, including support for collecting messages with timeouts.
 *
 * ## Basic Usage
 *
 * ```kotlin
 * http {
 *     webSocket("/chat") { session ->
 *         // Send a message
 *         session.send("Hello, World!")
 *
 *         // Receive a single message
 *         val response = session.receiveText()
 *         response shouldBe "Echo: Hello, World!"
 *     }
 * }
 * ```
 *
 * ## Collecting Multiple Messages
 *
 * ```kotlin
 * http {
 *     webSocket("/events") { session ->
 *         // Collect messages with a timeout
 *         val messages = session.collectTexts(
 *             count = 5,
 *             timeout = 10.seconds
 *         )
 *         messages.size shouldBe 5
 *     }
 * }
 * ```
 *
 * @property session The underlying Ktor WebSocket session.
 */
@HttpDsl
class StoveWebSocketSession(
  @PublishedApi internal val session: DefaultClientWebSocketSession
) {
  /**
   * Sends a text message through the WebSocket connection.
   *
   * @param message The text message to send.
   */
  @HttpDsl
  suspend fun send(message: String) {
    session.send(Frame.Text(message))
  }

  /**
   * Sends a binary message through the WebSocket connection.
   *
   * @param data The binary data to send.
   */
  @HttpDsl
  suspend fun send(data: ByteArray) {
    session.send(Frame.Binary(true, data))
  }

  /**
   * Sends a [StoveWebSocketMessage] through the WebSocket connection.
   *
   * @param message The message to send (either Text or Binary).
   */
  @HttpDsl
  suspend fun send(message: StoveWebSocketMessage) {
    when (message) {
      is StoveWebSocketMessage.Text -> send(message.content)
      is StoveWebSocketMessage.Binary -> send(message.content)
    }
  }

  /**
   * Receives the next text message from the WebSocket connection.
   *
   * @return The received text message, or null if the connection is closed
   *         or a non-text frame is received.
   */
  @HttpDsl
  suspend fun receiveText(): String? = session.incoming.receive().let { frame ->
    when (frame) {
      is Frame.Text -> frame.readText()
      else -> null
    }
  }

  /**
   * Receives the next binary message from the WebSocket connection.
   *
   * @return The received binary data, or null if the connection is closed
   *         or a non-binary frame is received.
   */
  @HttpDsl
  suspend fun receiveBinary(): ByteArray? = session.incoming.receive().let { frame ->
    when (frame) {
      is Frame.Binary -> frame.readBytes()
      else -> null
    }
  }

  /**
   * Receives the next message from the WebSocket connection as a [StoveWebSocketMessage].
   *
   * @return The received message (Text or Binary), or null if the connection is closed
   *         or an unsupported frame type is received.
   */
  @HttpDsl
  suspend fun receive(): StoveWebSocketMessage? = session.incoming.receive().let { frame ->
    when (frame) {
      is Frame.Text -> StoveWebSocketMessage.Text(frame.readText())
      is Frame.Binary -> StoveWebSocketMessage.Binary(frame.readBytes())
      else -> null
    }
  }

  /**
   * Attempts to receive a text message with a timeout.
   *
   * @param timeout The maximum duration to wait for a message.
   * @return An [Option] containing the received text message, or [None] if the timeout
   *         is reached or the connection is closed.
   */
  @HttpDsl
  suspend fun receiveTextWithTimeout(timeout: Duration = 5.seconds): Option<String> = try {
    withTimeout(timeout) {
      receiveText().toOption()
    }
  } catch (_: TimeoutCancellationException) {
    None
  }

  /**
   * Attempts to receive a binary message with a timeout.
   *
   * @param timeout The maximum duration to wait for a message.
   * @return An [Option] containing the received binary data, or [None] if the timeout
   *         is reached or the connection is closed.
   */
  @HttpDsl
  suspend fun receiveBinaryWithTimeout(timeout: Duration = 5.seconds): Option<ByteArray> = try {
    withTimeout(timeout) {
      receiveBinary().toOption()
    }
  } catch (_: TimeoutCancellationException) {
    None
  }

  /**
   * Collects text messages from the WebSocket connection.
   *
   * @param count The number of messages to collect.
   * @param timeout The maximum duration to wait for all messages.
   * @return A list of received text messages.
   */
  @HttpDsl
  suspend fun collectTexts(
    count: Int,
    timeout: Duration = 30.seconds
  ): List<String> = withTimeout(timeout) {
    val messages = mutableListOf<String>()
    repeat(count) {
      receiveText()?.let { messages.add(it) }
    }
    messages
  }

  /**
   * Collects binary messages from the WebSocket connection.
   *
   * @param count The number of messages to collect.
   * @param timeout The maximum duration to wait for all messages.
   * @return A list of received binary data.
   */
  @HttpDsl
  suspend fun collectBinaries(
    count: Int,
    timeout: Duration = 30.seconds
  ): List<ByteArray> = withTimeout(timeout) {
    val messages = mutableListOf<ByteArray>()
    repeat(count) {
      receiveBinary()?.let { messages.add(it) }
    }
    messages
  }

  /**
   * Creates a Flow of incoming text messages.
   *
   * The flow will emit messages until the WebSocket connection is closed.
   *
   * @return A [Flow] of text messages.
   */
  @HttpDsl
  fun incomingTexts(): Flow<String> = session.incoming
    .receiveAsFlow()
    .filterIsInstance<Frame.Text>()
    .map { it.readText() }

  /**
   * Creates a Flow of incoming binary messages.
   *
   * The flow will emit messages until the WebSocket connection is closed.
   *
   * @return A [Flow] of binary data.
   */
  @HttpDsl
  fun incomingBinaries(): Flow<ByteArray> = session.incoming
    .receiveAsFlow()
    .filterIsInstance<Frame.Binary>()
    .map { it.readBytes() }

  /**
   * Creates a Flow of all incoming messages as [StoveWebSocketMessage].
   *
   * The flow will emit messages until the WebSocket connection is closed.
   *
   * @return A [Flow] of [StoveWebSocketMessage].
   */
  @HttpDsl
  fun incoming(): Flow<StoveWebSocketMessage> = session.incoming
    .receiveAsFlow()
    .mapNotNull { frame ->
      when (frame) {
        is Frame.Text -> StoveWebSocketMessage.Text(frame.readText())
        is Frame.Binary -> StoveWebSocketMessage.Binary(frame.readBytes())
        else -> null
      }
    }

  /**
   * Closes the WebSocket connection gracefully.
   *
   * @param reason Optional close reason message.
   */
  @HttpDsl
  suspend fun close(reason: String = "Test completed") {
    session.close(CloseReason(CloseReason.Codes.NORMAL, reason))
  }

  /**
   * Provides access to the underlying Ktor WebSocket session for advanced use cases.
   *
   * @param block The block to execute with the underlying session.
   * @return The result of the block.
   */
  @HttpDsl
  suspend fun <T> underlyingSession(
    block: suspend DefaultClientWebSocketSession.() -> T
  ): T = block(session)
}

/**
 * Establishes a WebSocket connection and executes the provided block.
 *
 * ## Basic Usage
 *
 * ```kotlin
 * http {
 *     webSocket("/chat") { session ->
 *         session.send("Hello!")
 *         val response = session.receiveText()
 *         response shouldBe "Echo: Hello!"
 *     }
 * }
 * ```
 *
 * ## With Headers and Token
 *
 * ```kotlin
 * http {
 *     webSocket(
 *         uri = "/secure-chat",
 *         headers = mapOf("X-Custom-Header" to "value"),
 *         token = "jwt-token".some()
 *     ) { session ->
 *         session.send("Authenticated message")
 *     }
 * }
 * ```
 *
 * @param uri The WebSocket endpoint URI (e.g., "/chat").
 * @param headers Optional HTTP headers to send with the upgrade request.
 * @param token Optional bearer token for authentication.
 * @param block The test block to execute with the WebSocket session.
 * @return The [HttpSystem] for fluent chaining.
 */
@HttpDsl
suspend fun HttpSystem.webSocket(
  uri: String,
  headers: Map<String, String> = mapOf(),
  token: Option<String> = None,
  block: suspend StoveWebSocketSession.() -> Unit
): HttpSystem {
  ktorHttpClient.webSocket(
    urlString = buildWebSocketUrl(uri),
    request = {
      headers.forEach { (key, value) -> this.headers.append(key, value) }
      token.map {
        this.headers.append(
          HttpSystem.Companion.HeaderConstants.AUTHORIZATION,
          HttpSystem.Companion.HeaderConstants.bearer(it)
        )
      }
    }
  ) {
    val stoveSession = StoveWebSocketSession(this)
    block(stoveSession)
  }
  return this
}

/**
 * Establishes a WebSocket connection and executes assertions on the session.
 *
 * This is an alias for [webSocket] with a clearer intent for assertion-focused tests.
 *
 * ## Usage
 *
 * ```kotlin
 * http {
 *     webSocketExpect("/notifications") { session ->
 *         val messages = session.collectTexts(count = 3)
 *         messages.size shouldBe 3
 *         messages.all { it.startsWith("notification:") } shouldBe true
 *     }
 * }
 * ```
 *
 * @param uri The WebSocket endpoint URI.
 * @param headers Optional HTTP headers.
 * @param token Optional bearer token.
 * @param expect The assertion block to execute.
 * @return The [HttpSystem] for fluent chaining.
 */
@HttpDsl
suspend fun HttpSystem.webSocketExpect(
  uri: String,
  headers: Map<String, String> = mapOf(),
  token: Option<String> = None,
  expect: suspend StoveWebSocketSession.() -> Unit
): HttpSystem = webSocket(uri, headers, token, expect)

/**
 * Establishes a raw WebSocket connection for advanced use cases.
 *
 * This method provides direct access to the Ktor WebSocket session
 * for scenarios where the simplified [StoveWebSocketSession] is not sufficient.
 *
 * ## Usage
 *
 * ```kotlin
 * http {
 *     webSocketRaw("/advanced") { session ->
 *         session.send(Frame.Text("raw frame"))
 *         for (frame in session.incoming) {
 *             when (frame) {
 *                 is Frame.Text -> println(frame.readText())
 *                 is Frame.Binary -> println(frame.readBytes().size)
 *                 else -> {}
 *             }
 *         }
 *     }
 * }
 * ```
 *
 * @param uri The WebSocket endpoint URI.
 * @param headers Optional HTTP headers.
 * @param token Optional bearer token.
 * @param block The block to execute with the raw Ktor WebSocket session.
 * @return The [HttpSystem] for fluent chaining.
 */
@HttpDsl
suspend fun HttpSystem.webSocketRaw(
  uri: String,
  headers: Map<String, String> = mapOf(),
  token: Option<String> = None,
  block: suspend DefaultClientWebSocketSession.() -> Unit
): HttpSystem {
  ktorHttpClient.webSocket(
    urlString = buildWebSocketUrl(uri),
    request = {
      headers.forEach { (key, value) -> this.headers.append(key, value) }
      token.map {
        this.headers.append(
          HttpSystem.Companion.HeaderConstants.AUTHORIZATION,
          HttpSystem.Companion.HeaderConstants.bearer(it)
        )
      }
    }
  ) {
    block()
  }
  return this
}

@PublishedApi
internal fun HttpSystem.buildWebSocketUrl(uri: String): String {
  val baseUrl = options.baseUrl
  val wsUrl = when {
    baseUrl.startsWith("https://") -> baseUrl.replace("https://", "wss://")
    baseUrl.startsWith("http://") -> baseUrl.replace("http://", "ws://")
    else -> "ws://$baseUrl"
  }
  return "$wsUrl${uri.ensureLeadingSlash()}"
}

private fun String.ensureLeadingSlash(): String = if (startsWith("/")) this else "/$this"
