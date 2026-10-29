package com.trendyol.stove.chaos

import com.trendyol.stove.system.closeAll
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.seconds

class TcpChaosProxyTest :
  FunSpec({
    test("forwards traffic, partitions the link, and heals it") {
      ProxyFixture().use { link ->
        link.roundTrip("before partition") shouldBe "before partition"
        link.fault(NetworkFault.Unavailable)
        link.assertNewConnectionRejected()
        link.heal()
        link.roundTrip("after healing") shouldBe "after healing"
      }
    }

    test("unavailability closes an established connection") {
      ProxyFixture().use { link ->
        link.send(7)
        link.receive() shouldBe 7
        link.fault(NetworkFault.Unavailable)
        link.assertConnectionClosed()
        link.heal()
        link.roundTrip("reconnected") shouldBe "reconnected"
      }
    }

    test("stall holds existing traffic and healing releases it on the same connection") {
      ProxyFixture().use { link ->
        link.send(1)
        link.receive() shouldBe 1
        link.fault(stall())
        link.send(2)
        link.assertNoResponse()
        link.heal()
        link.receive() shouldBe 2
      }
    }

    for (direction in TrafficDirection.entries) {
      test("latency gates $direction and healing releases the delayed chunk") {
        ProxyFixture().use { link ->
          link.fault(latency(30.seconds, direction))
          link.send(42)
          if (direction == TrafficDirection.Responses) link.assertRequestReachedServer()
          link.assertNoResponse()
          if (direction != TrafficDirection.Responses) link.serverHasReceivedRequest shouldBe false
          link.heal()
          link.receive() shouldBe 42
        }
      }
    }

    test("latency followed by stall preserves the buffered response until healing") {
      ProxyFixture().use { link ->
        link.fault(latency(30.seconds, TrafficDirection.Responses))
        link.send(42)
        link.assertRequestReachedServer()
        link.assertNoResponse()
        link.fault(stall())
        link.assertNoResponse()
        link.heal()
        link.receive() shouldBe 42
        link.send(43)
        link.receive() shouldBe 43
      }
    }

    test("close releases stalled workers and prevents restart") {
      ProxyFixture().use { link ->
        link.send(1)
        link.receive() shouldBe 1
        link.fault(stall())
        link.send(2)
        link.proxy.close()
        link.assertConnectionClosed()
        shouldThrow<IllegalStateException> { link.start() }
        link.proxy.close()
      }
    }

    test("partition tolerates connections disappearing concurrently") {
      ProxyFixture().use { link ->
        repeat(100) {
          link.roundTrip("connected") shouldBe "connected"
          // Each round trip closes its peer; worker removal races with fault application.
          link.fault(NetworkFault.Unavailable)
          link.heal()
        }
      }
    }

    test("start is idempotent only for the configured target") {
      ProxyFixture().use { link ->
        link.start()
        val differentTarget = link.proxy.endpoint
        shouldThrow<IllegalStateException> {
          link.proxy.start(differentTarget)
        }.message shouldBe "echo proxy already targets ${link.target}"
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

/** One echo target and one proxy; send/receive retain a connection while roundTrip opens a fresh one. */
private class ProxyFixture : Closeable {
  private val received = CountDownLatch(1)
  private val echo = EchoServer { received.countDown() }
  val proxy = TcpChaosProxy("echo")
  val target: TcpEndpoint get() = echo.endpoint
  private val connection = lazy { connect() }
  val serverHasReceivedRequest: Boolean get() = received.count == 0L

  init {
    start()
  }

  fun start() = proxy.start(target)

  fun fault(fault: NetworkFault) = proxy.applyFault(fault)

  fun heal() = proxy.heal()

  fun send(value: Int) = connection.value.getOutputStream().write(value)

  fun receive(): Int = connection.value.getInputStream().read()

  fun roundTrip(message: String): String = connect().use { socket ->
    val bytes = message.toByteArray()
    socket.getOutputStream().write(bytes)
    String(socket.getInputStream().readNBytes(bytes.size))
  }

  fun assertNoResponse() {
    val socket = connection.value
    socket.soTimeout = NO_RESPONSE_TIMEOUT_MILLIS
    try {
      shouldThrow<SocketTimeoutException> { receive() }
    } finally {
      socket.soTimeout = SOCKET_TIMEOUT_MILLIS
    }
  }

  fun assertRequestReachedServer() {
    received.await(2, TimeUnit.SECONDS) shouldBe true
  }

  fun assertConnectionClosed() = assertClosed(connection.value)

  fun assertNewConnectionRejected() = connect().use { socket ->
    try {
      socket.getOutputStream().write(1)
    } catch (_: SocketException) {
      return@use
    }
    assertClosed(socket)
  }

  private fun assertClosed(socket: Socket) {
    val response = try {
      socket.getInputStream().read()
    } catch (_: SocketException) {
      -1
    }
    response shouldBe -1
  }

  private fun connect(): Socket = Socket(proxy.endpoint.host, proxy.endpoint.port).apply {
    soTimeout = SOCKET_TIMEOUT_MILLIS
  }

  override fun close(): Unit = runBlocking {
    closeAll(
      { if (connection.isInitialized()) connection.value.close() },
      { proxy.close() },
      { echo.close() }
    )
  }
}

private class EchoServer(private val onReceive: () -> Unit = {}) : Closeable {
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
          val input = socket.getInputStream()
          val output = socket.getOutputStream()
          while (true) {
            val value = input.read()
            if (value < 0) break
            onReceive()
            output.write(value)
          }
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

private const val NO_RESPONSE_TIMEOUT_MILLIS = 150
