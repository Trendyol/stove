package stove.ktor.gateway.internal

import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.selects.select
import stove.ktor.gateway.GatewayTransport
import java.io.IOException

/** Raw frames preserve fragmentation; queues and frame limits bound memory independently of message length. */
internal class GatewayWebSocketRelay(private val policy: GatewayTransport.WebSocket) {
  suspend fun relay(browser: WebSocketSession, upstream: WebSocketSession, revoked: Deferred<Unit>) {
    try {
      transfer(browser, upstream, revoked)
    } catch (error: SocketPolicyFailure) {
      finish(browser, upstream, error.reason)
    } catch (error: FrameTooBigException) {
      finish(browser, upstream, CloseReason(CloseReason.Codes.TOO_BIG, "Frame limit exceeded"))
    } catch (error: ProtocolViolationException) {
      finish(browser, upstream, CloseReason(CloseReason.Codes.PROTOCOL_ERROR, "Invalid frame"))
    } catch (error: IOException) {
      finish(browser, upstream, CloseReason(CloseReason.Codes.INTERNAL_ERROR, "Peer disconnected"))
    } catch (error: CancellationException) {
      finish(browser, upstream, CloseReason(CloseReason.Codes.GOING_AWAY, "Gateway connection ended"))
      throw error
    } finally {
      browser.cancel()
      upstream.cancel()
    }
  }

  private suspend fun transfer(browser: WebSocketSession, upstream: WebSocketSession, revoked: Deferred<Unit>): Unit = supervisorScope {
    val activity = Channel<Unit>(Channel.CONFLATED)
    val toUpstream = async { copy(browser, upstream, activity) }
    val toBrowser = async { copy(upstream, browser, activity) }
    try {
      var closing = false
      while (!closing) {
        val alive = withTimeoutOrNull(policy.idleTimeout) {
          select<Boolean> {
            revoked.onAwait { throw SocketPolicyFailure(CloseReason.Codes.VIOLATED_POLICY, "Gateway authorization ended") }
            activity.onReceive { false }
            toUpstream.onAwait { true }
            toBrowser.onAwait { true }
          }
        } ?: throw SocketPolicyFailure(CloseReason.Codes.GOING_AWAY, "WebSocket idle timeout")
        closing = alive
      }
      // The first close is forwarded. Give the peer a bounded opportunity to return its close.
      withTimeoutOrNull(policy.closeTimeout) {
        toUpstream.await()
        toBrowser.await()
      }
    } finally {
      toUpstream.cancel()
      toBrowser.cancel()
      activity.close()
    }
  }

  private suspend fun copy(source: WebSocketSession, target: WebSocketSession, activity: Channel<Unit>) {
    val validation = GatewayFrameValidation(policy)
    for (frame in source.incoming) {
      validation.accept(frame)
      activity.trySend(Unit)
      val sent = withTimeoutOrNull(policy.idleTimeout) {
        target.send(frame)
        target.flush()
        true
      }
      if (sent != true) throw SocketPolicyFailure(CloseReason.Codes.GOING_AWAY, "WebSocket peer is not reading")
      if (frame is Frame.Close) return
    }
    throw SocketPolicyFailure(CloseReason.Codes.INTERNAL_ERROR, "Peer disconnected without a close frame")
  }

  private suspend fun finish(browser: WebSocketSession, upstream: WebSocketSession, reason: CloseReason) = withContext(NonCancellable) {
    // Cleanup is bounded even after cancellation or when one peer no longer reads.
    withTimeoutOrNull(policy.closeTimeout) {
      supervisorScope {
        listOf(browser, upstream).map { peer ->
          async {
            try {
              peer.send(Frame.Close(reason))
              peer.flush()
            } catch (_: Exception) {
              // Closing is best effort; the relay's original failure/cancellation remains authoritative.
            }
          }
        }.awaitAll()
      }
    }
    Unit
  }
}

internal class SocketPolicyFailure(code: CloseReason.Codes, message: String) : RuntimeException(message) {
  val reason = CloseReason(code, message)
}
