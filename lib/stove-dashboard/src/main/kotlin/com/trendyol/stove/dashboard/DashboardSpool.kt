package com.trendyol.stove.dashboard

import com.google.protobuf.CodedOutputStream
import com.trendyol.stove.dashboard.api.DashboardEvent
import java.io.Closeable
import java.io.IOException
import java.nio.channels.FileLock
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal const val MAX_BATCH_EVENTS = 100
internal const val MAX_BATCH_BYTES = 1024 * 1024
internal const val MAX_SPOOLED_EVENT_BYTES = 8 * 1024 * 1024

/** Failures are synchronous backpressure: the caller retains ownership of an unsaved event. */
class DashboardSpoolException(message: String, cause: Exception? = null) : IOException(message, cause)

data class DashboardDeliveryStatus(val pendingEvents: Long, val pendingBytes: Long, val lastError: String? = null)

internal data class SpooledEvent(val id: Long, val event: DashboardEvent)

/** The only in-memory evidence is one delivery batch, bounded by count and encoded bytes. */
internal class DashboardSpool(ingestion: DashboardIngestion, options: DashboardSpoolOptions) : Closeable {
  private val lock = ReentrantLock()
  val path: Path
  private val connection: Connection
  private val deliveryChannel: DashboardDeliveryLock

  init {
    Files.createDirectories(options.directory)
    val endpoint = when (ingestion) {
      is DashboardIngestion.Grpc -> "grpc://${ingestion.host}:${ingestion.port}"
      is DashboardIngestion.Http -> ingestion.eventsUri.toString()
    }
    val name = MessageDigest.getInstance("SHA-256").digest(endpoint.toByteArray(Charsets.UTF_8))
      .joinToString("") { "%02x".format(it) }
    path = options.directory.toRealPath().resolve("$name.db")
    connection = DriverManager.getConnection("jdbc:sqlite:$path")
    try {
      connection.createStatement().use { statement ->
        statement.execute("PRAGMA busy_timeout=10000")
        statement.execute("PRAGMA journal_mode=DELETE")
        statement.execute("PRAGMA synchronous=FULL")
        statement.execute("PRAGMA cache_size=-1024")
        statement.execute("PRAGMA temp_store=FILE")
        // Reserve more than half the quota for the rollback journal and filesystem overhead.
        val pages = options.maxBytes / 4096 / 100 * 45
        statement.executeQuery("PRAGMA max_page_count=$pages").use { result ->
          check(result.next() && result.getLong(1) <= pages) { "Existing spool exceeds the configured disk quota" }
        }
        statement.execute("CREATE TABLE IF NOT EXISTS run_sequences (run_id TEXT PRIMARY KEY, sequence INTEGER NOT NULL)")
        statement.execute(
          "CREATE TABLE IF NOT EXISTS pending (id INTEGER PRIMARY KEY AUTOINCREMENT, run_id TEXT NOT NULL, payload BLOB NOT NULL, bytes INTEGER NOT NULL)"
        )
        statement.execute("CREATE INDEX IF NOT EXISTS pending_run ON pending(run_id)")
        statement.execute(
          "CREATE TABLE IF NOT EXISTS counters (id INTEGER PRIMARY KEY CHECK(id=1), events INTEGER NOT NULL, bytes INTEGER NOT NULL)"
        )
        statement.execute("INSERT OR IGNORE INTO counters VALUES (1, 0, 0)")
      }
      deliveryChannel = DashboardDeliveryLock.open(path.resolveSibling("$name.delivery.lock"))
    } catch (error: Exception) {
      connection.close()
      throw DashboardSpoolException("Cannot open dashboard spool $path", error)
    }
  }

  fun append(event: DashboardEvent) = transaction {
    val previous = connection.prepareStatement(
      "SELECT sequence FROM run_sequences WHERE run_id=?"
    ).use { query ->
      query.setString(1, event.runId)
      query.executeQuery().use { rows -> if (rows.next()) rows.getLong(1) else 0L }
    }
    val identified = event.toBuilder().setEventId(UUID.randomUUID().toString())
      .setSequence(Math.addExact(previous, 1)).build()
    if (identified.serializedSize > MAX_SPOOLED_EVENT_BYTES) {
      throw DashboardSpoolException("Dashboard event exceeds the 8 MiB spool record limit")
    }
    val payload = identified.toByteArray()
    connection.prepareStatement(
      "INSERT INTO pending(run_id, payload, bytes) VALUES (?, ?, ?)"
    ).use { insert ->
      insert.setString(1, identified.runId)
      insert.setBytes(2, payload)
      insert.setInt(3, payload.size)
      insert.executeUpdate()
    }
    connection.prepareStatement(
      "INSERT INTO run_sequences VALUES (?, ?) ON CONFLICT(run_id) DO UPDATE SET sequence=excluded.sequence"
    ).use { update ->
      update.setString(1, identified.runId)
      update.setLong(2, identified.sequence)
      update.executeUpdate()
    }
    connection.prepareStatement(
      "UPDATE counters SET events=events+1, bytes=bytes+? WHERE id=1"
    ).use { update ->
      update.setInt(1, payload.size)
      update.executeUpdate()
    }
  }

  /** Ordered events from the oldest pending run. Independent runs may be interleaved on disk. */
  fun peek(limit: Int = MAX_BATCH_EVENTS): List<SpooledEvent> = lock.withLock {
    connection.createStatement().use { query ->
      query.executeQuery(
        "SELECT id, run_id, payload, bytes FROM pending " +
          "WHERE run_id=(SELECT run_id FROM pending ORDER BY id LIMIT 1) ORDER BY id LIMIT $limit"
      ).use { rows ->
        val batch = mutableListOf<SpooledEvent>()
        var bytes = 0
        var run: String? = null
        while (rows.next()) {
          val runId = rows.getString(2)
          if (run != null && run != runId) break
          val length = rows.getInt(4)
          val size = 1 + CodedOutputStream.computeUInt32SizeNoTag(length) + length
          if (batch.isNotEmpty() && bytes + size > MAX_BATCH_BYTES) break
          batch.add(SpooledEvent(rows.getLong(1), DashboardEvent.parseFrom(rows.getBytes(3))))
          run = runId
          bytes += size
          if (bytes >= MAX_BATCH_BYTES) break
        }
        batch
      }
    }
  }

  fun acknowledge(events: List<SpooledEvent>) = transaction {
    for (record in events) {
      connection.prepareStatement(
        "DELETE FROM pending WHERE id=? RETURNING bytes"
      ).use { delete ->
        delete.setLong(1, record.id)
        delete.executeQuery().use { row ->
          if (row.next()) {
            connection.prepareStatement(
              "UPDATE counters SET events=events-1, bytes=bytes-? WHERE id=1"
            ).use { update ->
              update.setInt(1, row.getInt(1))
              update.executeUpdate()
            }
          }
        }
      }
      if (record.event.hasRunEnded()) {
        connection.prepareStatement(
          "DELETE FROM run_sequences WHERE run_id=? AND NOT EXISTS (SELECT 1 FROM pending WHERE run_id=?)"
        ).use { delete ->
          delete.setString(1, record.event.runId)
          delete.setString(2, record.event.runId)
          delete.executeUpdate()
        }
      }
    }
  }

  fun status(): DashboardDeliveryStatus = lock.withLock {
    connection.createStatement().use { query ->
      query.executeQuery("SELECT events, bytes FROM counters WHERE id=1").use { row ->
        check(row.next())
        DashboardDeliveryStatus(row.getLong(1), row.getLong(2))
      }
    }
  }

  fun tryAcquireDelivery(): FileLock? = deliveryChannel.tryAcquire()

  private fun <T> transaction(block: () -> T): T = lock.withLock {
    try {
      connection.createStatement().use { it.execute("BEGIN IMMEDIATE") }
      try {
        val result = block()
        connection.createStatement().use { it.execute("COMMIT") }
        return@withLock result
      } catch (error: Exception) {
        runCatching { connection.createStatement().use { it.execute("ROLLBACK") } }
        throw error
      }
    } catch (error: Exception) {
      throw DashboardSpoolException("Dashboard spool write failed at $path; pending evidence is retained: ${error.message}", error)
    }
  }

  override fun close() = lock.withLock {
    try {
      connection.close()
    } finally {
      deliveryChannel.close()
    }
  }
}
