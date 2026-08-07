package com.trendyol.stove.chaos

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class TcpChaosProxyTest :
  FunSpec({
    test("forwards traffic, partitions the link, and heals it") {
      EchoServer().use { echoServer ->
        TcpChaosProxy("echo").use { proxy ->
          proxy.start(echoServer.endpoint)

          roundTrip(proxy.endpoint, "before partition") shouldBe "before partition"

          proxy.partition()
          connectionIsRejected(proxy.endpoint) shouldBe true

          proxy.heal()
          roundTrip(proxy.endpoint, "after healing") shouldBe "after healing"
        }
      }
    }

    test("start is idempotent only for the configured target") {
      EchoServer().use { first ->
        EchoServer().use { second ->
          TcpChaosProxy("echo").use { proxy ->
            proxy.start(first.endpoint)
            proxy.start(first.endpoint)

            shouldThrow<IllegalStateException> {
              proxy.start(second.endpoint)
            }.message shouldBe "echo proxy already targets ${first.endpoint}"
          }
        }
      }
    }

    test("endpoint is unavailable before the proxy starts") {
      TcpChaosProxy("echo").use { proxy ->
        shouldThrow<IllegalStateException> { proxy.endpoint }
          .message shouldBe "echo proxy has not been started"
      }
    }

    test("TCP endpoint formats authorities and URIs") {
      TcpEndpoint("127.0.0.1", 8080).apply {
        authority shouldBe "127.0.0.1:8080"
        uri("http").toString() shouldBe "http://127.0.0.1:8080"
      }
      TcpEndpoint("::1", 8080).authority shouldBe "[::1]:8080"
    }
  })

private fun roundTrip(
  endpoint: TcpEndpoint,
  message: String
): String = Socket(endpoint.host, endpoint.port).use { socket ->
  socket.soTimeout = SOCKET_TIMEOUT_MILLIS
  val bytes = message.toByteArray()
  socket.getOutputStream().apply {
    write(bytes)
    flush()
  }
  String(socket.getInputStream().readNBytes(bytes.size))
}

private fun connectionIsRejected(endpoint: TcpEndpoint): Boolean =
  Socket(endpoint.host, endpoint.port).use { socket ->
    socket.soTimeout = SOCKET_TIMEOUT_MILLIS
    runCatching {
      socket.getOutputStream().apply {
        write(1)
        flush()
      }
      socket.getInputStream().read()
    }.fold(onSuccess = { it == -1 }, onFailure = { true })
  }

private class EchoServer : Closeable {
  private val running = AtomicBoolean(true)
  private val server = ServerSocket(0, SERVER_BACKLOG, InetAddress.getByName(LOOPBACK_HOST))
  private val executor = Executors.newSingleThreadExecutor()

  val endpoint = TcpEndpoint(LOOPBACK_HOST, server.localPort)

  init {
    executor.execute(::acceptConnections)
  }

  private fun acceptConnections() {
    while (running.get()) {
      try {
        server.accept().use { socket ->
          socket.getInputStream().copyTo(socket.getOutputStream())
        }
      } catch (_: SocketException) {
        if (running.get()) throw IllegalStateException("echo server accept failed")
      }
    }
  }

  override fun close() {
    running.set(false)
    server.close()
    executor.shutdownNow()
    executor.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)
  }
}

private const val LOOPBACK_HOST = "127.0.0.1"
private const val SERVER_BACKLOG = 10
private const val SOCKET_TIMEOUT_MILLIS = 1_000
private const val SHUTDOWN_TIMEOUT_SECONDS = 5L
