package stove.ktor.bff.fixtures

import io.ktor.http.*
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.*
import io.ktor.utils.io.*
import kotlinx.coroutines.Dispatchers

/** Send malformed upgrade headers without a client plugin repairing them. */
suspend fun GatewayProtocolFixture.rawHandshake(
  method: String = "GET",
  body: String = "",
  configure: MutableList<Pair<String, String>>.() -> Unit = {}
): HttpStatusCode {
  val target = Url(origin)
  val headers = mutableListOf(
    "Host" to target.hostWithPort,
    "Origin" to origin,
    "Connection" to "Upgrade",
    "Upgrade" to "websocket",
    "Sec-WebSocket-Version" to "13",
    "Sec-WebSocket-Key" to "dGhlIHNhbXBsZSBub25jZQ=="
  ).apply(configure)
  val request = buildString {
    append("$method /sockets/echo HTTP/1.1\r\n")
    headers.forEach { (name, value) -> append("$name: $value\r\n") }
    append("\r\n$body")
  }
  return SelectorManager(Dispatchers.IO).use { selector ->
    aSocket(selector).tcp().connect(target.host, target.port).use { socket ->
      socket.openWriteChannel(autoFlush = true).writeStringUtf8(request)
      val line = checkNotNull(socket.openReadChannel().readLineStrict(limit = 4096))
      HttpStatusCode.fromValue(line.split(' ')[1].toInt())
    }
  }
}
