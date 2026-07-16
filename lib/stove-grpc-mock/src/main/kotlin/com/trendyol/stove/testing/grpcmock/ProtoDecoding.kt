package com.trendyol.stove.testing.grpcmock

import com.google.protobuf.Message
import com.google.protobuf.Parser
import io.grpc.MethodDescriptor
import java.io.ByteArrayInputStream

internal sealed interface PayloadDecodeResult<out T> {
  data class Decoded<T>(
    val value: T
  ) : PayloadDecodeResult<T>

  data class Failed(
    val error: Throwable
  ) : PayloadDecodeResult<Nothing>
}

internal class PayloadDecoder<T>(
  val description: String,
  private val decodeBytes: (ByteArray) -> T
) {
  fun decode(bytes: ByteArray): PayloadDecodeResult<T> = try {
    PayloadDecodeResult.Decoded(decodeBytes(bytes))
  } catch (error: Exception) {
    PayloadDecodeResult.Failed(error)
  }
}

internal fun <T : Message> Parser<T>.payloadDecoder(): PayloadDecoder<T> =
  PayloadDecoder("the supplied protobuf parser") { bytes -> parseFrom(bytes) }

internal fun <RequestT : Message, ResponseT> MethodDescriptor<RequestT, ResponseT>.requestPayloadDecoder(): PayloadDecoder<RequestT> =
  PayloadDecoder("the request marshaller for '$fullMethodName'") { bytes ->
    parseRequest(ByteArrayInputStream(bytes))
  }

internal fun Message.singleLine(): String = toString().replace(Regex("\\s+"), " ").trim()

internal fun Throwable.conciseDecodeError(): String {
  val root = generateSequence(this) { it.cause }.last()
  val type = root::class.simpleName ?: "Decode error"
  return root.message
    ?.takeIf { it.isNotBlank() }
    ?.let { "$type: $it" }
    ?: type
}
