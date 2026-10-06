package stove.ktor.gateway

import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.http.*
import io.ktor.http.content.OutgoingContent
import io.ktor.utils.io.*
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.readByteArray
import java.io.IOException
import kotlin.time.Duration

/** A single-consumer stream. Only an explicit [readBytes] call collects the complete body. */
class GatewayBody private constructor(
  private var source: BodySource,
  private val limit: Int,
  private val failureStatus: HttpStatusCode,
  private val transferLimit: Long = limit.toLong(),
  private val idleTimeout: Duration = Duration.INFINITE
) {
  val contentLength: Long? get() = source.length

  /** Buffer within both limits, retaining bytes for subsequent forwarding. Returns a copy for application use. */
  suspend fun readBytes(maxBytes: Int): ByteArray {
    require(maxBytes > 0) { "A positive body buffer limit is required" }
    val maximum = minOf(limit, maxBytes)
    checkLength(maximum.toLong())
    val current = source
    if (current is BodySource.Bytes) return current.value.copyOf()
    val channel = current.open()
    val bytes = try {
      read {
        val bytes = channel.readBuffer(maximum.toLong() + 1).readByteArray()
        channel.closedCause?.let { throw it }
        bytes
      }
    } finally {
      channel.cancel()
    }
    if (bytes.size > maximum) throw GatewayFailure(failureStatus, "Gateway body limit exceeded")
    checkComplete(bytes.size.toLong())
    source = BodySource.Bytes(bytes)
    return bytes.copyOf()
  }

  /** Copy incrementally with backpressure; the caller owns the destination channel. */
  suspend fun writeTo(destination: ByteWriteChannel) {
    checkLength()
    val channel = source.open()
    val buffer = ByteArray(8 * 1024)
    var transferred = 0L
    try {
      while (read { progressing { channel.awaitContent() } }) {
        val count = read { channel.readAvailable(buffer) }
        if (count < 0) break
        transferred += count
        if (transferred > transferLimit) throw GatewayFailure(failureStatus, "Gateway body limit exceeded")
        progressing {
          destination.writeFully(buffer, 0, count)
          destination.flush()
        }
      }
      checkComplete(transferred)
    } catch (error: Throwable) {
      // A truncated chunked response must not be closed as a successful end-of-stream.
      destination.close(error)
      throw error
    } finally {
      channel.cancel()
    }
  }

  internal fun checkLength(maximum: Long = transferLimit) {
    val length = contentLength ?: return
    if (length > maximum) throw GatewayFailure(failureStatus, "Gateway body limit exceeded")
  }

  internal fun outgoing(): OutgoingContent {
    checkLength()
    val current = source
    if (current is BodySource.Bytes) {
      return object : OutgoingContent.ByteArrayContent() {
        override val contentLength = current.length
        override fun bytes() = current.value
      }
    }
    return object : OutgoingContent.WriteChannelContent() {
      override val contentLength = current.length
      override suspend fun writeTo(channel: ByteWriteChannel) = this@GatewayBody.writeTo(channel)
    }
  }

  internal fun cancel() = source.cancel()

  private suspend fun <T : Any> progressing(block: suspend () -> T): T =
    withTimeoutOrNull(idleTimeout) { block() } ?: throw GatewayFailure(HttpStatusCode.GatewayTimeout, "Gateway stream idle timeout")

  private fun checkComplete(transferred: Long) {
    val expected = contentLength ?: return
    if (transferred != expected) {
      val status = if (failureStatus == HttpStatusCode.BadGateway) failureStatus else HttpStatusCode.BadRequest
      throw GatewayFailure(status, "Incomplete gateway body")
    }
  }

  private suspend fun <T> read(block: suspend () -> T): T = try {
    block()
  } catch (error: HttpRequestTimeoutException) {
    throw GatewayFailure(HttpStatusCode.GatewayTimeout, "Upstream request timed out", error)
  } catch (error: IOException) {
    val status = if (failureStatus == HttpStatusCode.BadGateway) failureStatus else HttpStatusCode.BadRequest
    throw GatewayFailure(status, "Gateway body transfer failed", error)
  }

  internal companion object {
    fun bytes(value: ByteArray, limit: Int, status: HttpStatusCode) = GatewayBody(BodySource.Bytes(value), limit, status)

    fun events(channel: ByteReadChannel, limit: Int, idleTimeout: Duration) =
      GatewayBody(BodySource.Stream(channel, null), limit, HttpStatusCode.BadGateway, Long.MAX_VALUE, idleTimeout)

    fun stream(channel: ByteReadChannel, length: Long?, limit: Int, status: HttpStatusCode): GatewayBody {
      require(length == null || length >= 0) { "Body length cannot be negative" }
      val source = if (length == 0L || (length == null && channel.isClosedForRead && channel.closedCause == null)) {
        channel.cancel()
        BodySource.Bytes(byteArrayOf())
      } else {
        BodySource.Stream(channel, length)
      }
      return GatewayBody(source, limit, status)
    }
  }
}

private sealed interface BodySource {
  val length: Long?
  fun open(): ByteReadChannel
  fun cancel()

  class Bytes(val value: ByteArray) : BodySource {
    override val length = value.size.toLong()
    override fun open() = ByteReadChannel(value)
    override fun cancel() = Unit
  }

  class Stream(private val channel: ByteReadChannel, override val length: Long?) : BodySource {
    private var consumed = false

    override fun open(): ByteReadChannel {
      check(!consumed) { "A gateway stream can only be consumed once; readBytes explicitly to retain it" }
      consumed = true
      return channel
    }

    override fun cancel() = channel.cancel()
  }
}
