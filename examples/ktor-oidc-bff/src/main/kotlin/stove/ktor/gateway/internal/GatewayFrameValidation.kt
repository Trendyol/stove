package stove.ktor.gateway.internal

import io.ktor.websocket.*
import stove.ktor.gateway.GatewayTransport

/** Track fragmented message size and UTF-8 state using constant memory. */
internal class GatewayFrameValidation(private val policy: GatewayTransport.WebSocket) {
  private var messageBytes = 0L
  private val text = Utf8Validation()

  fun accept(frame: Frame) {
    if (frame.rsv1 || frame.rsv2 || frame.rsv3) fail(CloseReason.Codes.PROTOCOL_ERROR, "Extensions are not enabled")
    if (frame.data.size > policy.maxFrameBytes) fail(CloseReason.Codes.TOO_BIG, "Frame limit exceeded")
    if (frame.frameType.controlFrame) {
      if (!frame.fin || frame.data.size > 125) fail(CloseReason.Codes.PROTOCOL_ERROR, "Invalid control frame")
      if (frame is Frame.Close) validateClose(frame.data)
      return
    }
    messageBytes += frame.data.size
    if (messageBytes > policy.maxMessageBytes) fail(CloseReason.Codes.TOO_BIG, "Message limit exceeded")
    if (frame is Frame.Text) text.accept(frame.data, frame.fin)
    if (frame.fin) messageBytes = 0
  }

  private fun validateClose(data: ByteArray) {
    if (data.size == 1) fail(CloseReason.Codes.PROTOCOL_ERROR, "Invalid close payload")
    if (data.isEmpty()) return
    val code = ((data[0].toInt() and 255) shl 8) or (data[1].toInt() and 255)
    if (code !in setOf(1000, 1001, 1002, 1003, 1007, 1008, 1009, 1010, 1011, 1012, 1013, 1014) && code !in 3000..4999) {
      fail(CloseReason.Codes.PROTOCOL_ERROR, "Invalid close code")
    }
    Utf8Validation().accept(data.copyOfRange(2, data.size), true)
  }
}

private class Utf8Validation {
  private var remaining = 0
  private var minimum = 0x80
  private var maximum = 0xbf

  fun accept(bytes: ByteArray, final: Boolean) {
    for (byte in bytes) {
      val value = byte.toInt() and 255
      if (remaining > 0) {
        if (value !in minimum..maximum) invalid()
        remaining--
        minimum = 0x80
        maximum = 0xbf
        continue
      }
      when (value) {
        in 0..0x7f -> Unit

        in 0xc2..0xdf -> remaining = 1

        in 0xe0..0xef -> {
          remaining = 2
          minimum = if (value == 0xe0) 0xa0 else 0x80
          maximum = if (value == 0xed) 0x9f else 0xbf
        }

        in 0xf0..0xf4 -> {
          remaining = 3
          minimum = if (value == 0xf0) 0x90 else 0x80
          maximum = if (value == 0xf4) 0x8f else 0xbf
        }

        else -> invalid()
      }
    }
    if (final && remaining != 0) invalid()
  }

  private fun invalid(): Nothing = fail(CloseReason.Codes.NOT_CONSISTENT, "Invalid UTF-8")
}

private fun fail(code: CloseReason.Codes, message: String): Nothing = throw SocketPolicyFailure(code, message)
