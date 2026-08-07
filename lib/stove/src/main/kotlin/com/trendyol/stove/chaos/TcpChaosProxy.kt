package com.trendyol.stove.chaos

import java.io.Closeable
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

private const val MIN_TCP_PORT = 1
private const val MAX_TCP_PORT = 65_535
private const val DEFAULT_LOOPBACK_HOST = "127.0.0.1"
private const val TCP_SERVER_BACKLOG = 50
private const val TCP_CONNECT_TIMEOUT_MILLIS = 1_000
private const val PROXY_SHUTDOWN_TIMEOUT_SECONDS = 5L

data class TcpEndpoint(
  val host: String,
  val port: Int
) {
  init {
    require(host.isNotBlank()) { "TCP endpoint host cannot be blank" }
    require(port in MIN_TCP_PORT..MAX_TCP_PORT) {
      "TCP endpoint port must be between $MIN_TCP_PORT and $MAX_TCP_PORT"
    }
  }

  val authority: String
    get() = if (':' in host) "[$host]:$port" else "$host:$port"

  fun uri(scheme: String): URI {
    require(scheme.isNotBlank()) { "URI scheme cannot be blank" }
    return URI(scheme, null, host, port, null, null, null)
  }
}

/**
 * In-process TCP proxy for application-only network partition experiments.
 *
 * Point the application at [endpoint] while test systems keep using the dependency's direct
 * endpoint. Calling [partition] drops active connections and rejects new ones; [heal] allows new
 * connections without restarting the physical dependency.
 */
class TcpChaosProxy(
  override val name: String,
  private val listenHost: String = DEFAULT_LOOPBACK_HOST
) : Closeable,
  NetworkPartitionTarget {
  private val running = AtomicBoolean(false)
  private val partitioned = AtomicBoolean(false)
  private val connections = ConcurrentHashMap.newKeySet<Connection>()
  private val executor = Executors.newCachedThreadPool(DaemonThreadFactory("$name-chaos-proxy"))
  private val lifecycleLock = ReentrantLock()
  private lateinit var targetSocketAddress: InetSocketAddress
  private var targetEndpoint: TcpEndpoint? = null
  private lateinit var server: ServerSocket

  val endpoint: TcpEndpoint
    get() {
      check(running.get()) { "$name proxy has not been started" }
      return TcpEndpoint(listenHost, server.localPort)
    }

  fun start(target: TcpEndpoint): Unit = lifecycleLock.withLock {
    if (running.get()) {
      check(targetEndpoint == target) { "$name proxy already targets $targetEndpoint" }
      return
    }
    check(!executor.isShutdown) { "$name proxy cannot be restarted after close" }

    targetEndpoint = target
    targetSocketAddress = InetSocketAddress(target.host, target.port)
    server = ServerSocket(0, TCP_SERVER_BACKLOG, InetAddress.getByName(listenHost))
    running.set(true)
    try {
      executor.execute(::acceptConnections)
    } catch (failure: RejectedExecutionException) {
      running.set(false)
      server.close()
      throw failure
    }
  }

  override fun partition() {
    partitioned.set(true)
    connections.toList().forEach(Connection::close)
  }

  override fun heal() {
    partitioned.set(false)
  }

  private fun acceptConnections() {
    while (running.get()) {
      try {
        val source = server.accept()
        if (!running.get() || partitioned.get()) {
          source.close()
          continue
        }
        executeOrClose(source) { connect(source) }
      } catch (_: SocketException) {
        if (running.get()) throw IllegalStateException("$name proxy accept failed")
      }
    }
  }

  private fun connect(source: Socket) {
    val destination = Socket()
    try {
      destination.connect(targetSocketAddress, TCP_CONNECT_TIMEOUT_MILLIS)
      if (partitioned.get()) {
        source.close()
        destination.close()
        return
      }

      source.tcpNoDelay = true
      destination.tcpNoDelay = true
      val connection = Connection(source, destination)
      connections += connection
      if (partitioned.get()) {
        connection.close()
        connections -= connection
        return
      }
      executeOrClose(connection) { pump(connection, source, destination) }
      executeOrClose(connection) { pump(connection, destination, source) }
    } catch (_: Exception) {
      source.close()
      destination.close()
    }
  }

  private fun pump(
    connection: Connection,
    source: Socket,
    destination: Socket
  ) {
    try {
      source.getInputStream().use { input ->
        destination.getOutputStream().use(input::copyTo)
      }
    } catch (_: Exception) {
      // Connection shutdown and injected partitions are expected in this proxy.
    } finally {
      connection.close()
      connections -= connection
    }
  }

  override fun close() {
    val shouldAwait = lifecycleLock.withLock {
      if (!running.getAndSet(false)) return
      partitioned.set(true)
      server.close()
      connections.toList().forEach(Connection::close)
      executor.shutdownNow()
      true
    }
    if (shouldAwait) {
      executor.awaitTermination(PROXY_SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }
  }

  private fun executeOrClose(
    resource: Closeable,
    task: () -> Unit
  ) {
    try {
      executor.execute { task() }
    } catch (_: RejectedExecutionException) {
      resource.close()
    }
  }

  private class Connection(
    private val source: Socket,
    private val destination: Socket
  ) : Closeable {
    private val closed = AtomicBoolean(false)

    override fun close() {
      if (!closed.compareAndSet(false, true)) return
      runCatching(source::close)
      runCatching(destination::close)
    }
  }

  private class DaemonThreadFactory(
    private val prefix: String
  ) : ThreadFactory {
    private val nextId = AtomicInteger()

    override fun newThread(task: Runnable): Thread =
      Thread(task, "$prefix-${nextId.getAndIncrement()}").apply { isDaemon = true }
  }
}
