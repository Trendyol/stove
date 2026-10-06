package stove.ktor.bff.fixtures

import io.ktor.http.*
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.*
import io.ktor.utils.io.*
import kotlinx.coroutines.Dispatchers
import java.io.EOFException

/** Inspect framing directly: CIO's HTTP decoder accepts EOF between chunks without the required final chunk. */
suspend fun withChunkedResponse(origin: String, path: String, test: suspend ChunkedResponse.() -> Unit) {
  val url = Url(origin)
  SelectorManager(Dispatchers.IO).use { selector ->
    aSocket(selector).tcp().connect(url.host, url.port).use { socket ->
      socket.openWriteChannel(autoFlush = true)
        .writeStringUtf8("GET $path HTTP/1.1\r\nHost: ${url.host}:${url.port}\r\nConnection: close\r\n\r\n")
      ChunkedResponse.read(socket.openReadChannel()).test()
    }
  }
}

class ChunkedResponse private constructor(val status: HttpStatusCode, private val channel: ByteReadChannel) {
  /** Empty bytes mean the explicit terminal chunk; an absent terminal chunk is a truncated response. */
  suspend fun readChunk(): ByteArray {
    val size = channel.line().substringBefore(';').toInt(16)
    check(size in 0..8192) { "Unexpected fixture chunk size: $size" }
    val bytes = ByteArray(size)
    channel.readFully(bytes)
    check(channel.line().isEmpty()) { "Missing chunk terminator" }
    return bytes
  }

  companion object {
    suspend fun read(channel: ByteReadChannel): ChunkedResponse {
      val status = HttpStatusCode.fromValue(channel.line().split(' ')[1].toInt())
      val headers = HeadersBuilder()
      var line = channel.line()
      while (line.isNotEmpty()) {
        headers.append(line.substringBefore(':'), line.substringAfter(':').trim())
        line = channel.line()
      }
      check(headers[HttpHeaders.TransferEncoding] == "chunked") { "Expected a chunked response" }
      return ChunkedResponse(status, channel)
    }
  }
}

private suspend fun ByteReadChannel.line(): String = readLineStrict(limit = 4096) ?: throw EOFException("Incomplete chunked response")
