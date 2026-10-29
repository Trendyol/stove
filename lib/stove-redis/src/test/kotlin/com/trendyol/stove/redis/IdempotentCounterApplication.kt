package com.trendyol.stove.redis

import com.sun.net.httpserver.HttpServer
import com.trendyol.stove.system.closeAll
import io.lettuce.core.RedisCommandTimeoutException
import io.lettuce.core.ScriptOutputType
import io.lettuce.core.api.StatefulRedisConnection
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.net.URI
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** A real HTTP boundary with bounded Redis retries and atomic operation-ID deduplication. */
internal class IdempotentCounterApplication(
  private val connection: StatefulRedisConnection<String, String>,
  private val counter: String
) : AutoCloseable {
  private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
  private val executor = Executors.newSingleThreadExecutor()
  val attempts = AtomicInteger()
  val endpoint: URI get() = URI("http://127.0.0.1:${server.address.port}")

  init {
    try {
      connection.setTimeout(java.time.Duration.ofMillis(250))
      server.executor = executor
      server.createContext("/health") { exchange ->
        val body = connection.sync().ping().toByteArray()
        exchange.sendResponseHeaders(200, body.size.toLong())
        exchange.responseBody.use { it.write(body) }
      }
      server.createContext("/increment") { exchange ->
        val operation = requireNotNull(exchange.requestHeaders.getFirst("Idempotency-Key"))
        val result = increment(operation)
        val body = (result?.toString() ?: "dependency timeout").toByteArray()
        exchange.responseHeaders.set("Content-Type", if (result == null) "text/plain" else "application/json")
        exchange.sendResponseHeaders(if (result == null) 504 else 200, body.size.toLong())
        exchange.responseBody.use { it.write(body) }
      }
      server.start()
    } catch (failure: Throwable) {
      runCatching(::close).exceptionOrNull()?.let(failure::addSuppressed)
      throw failure
    }
  }

  private fun increment(operation: String): Long? {
    repeat(2) {
      attempts.incrementAndGet()
      try {
        return connection.sync().eval(
          """
          local previous = redis.call('GET', KEYS[2])
          if previous then return tonumber(previous) end
          local value = redis.call('INCR', KEYS[1])
          redis.call('SET', KEYS[2], value)
          return value
          """.trimIndent(),
          ScriptOutputType.INTEGER,
          arrayOf(counter, "$counter:operation:$operation")
        )
      } catch (_: RedisCommandTimeoutException) {
        // Retry only this idempotent operation, with the same operation ID and a fixed attempt limit.
      }
    }
    return null
  }

  // The caller owns the Redis connection, including construction failures.
  override fun close(): Unit = runBlocking {
    closeAll(
      { server.stop(0) },
      {
        executor.shutdownNow()
        check(executor.awaitTermination(5, TimeUnit.SECONDS)) { "HTTP application executor did not terminate" }
      }
    )
  }
}
