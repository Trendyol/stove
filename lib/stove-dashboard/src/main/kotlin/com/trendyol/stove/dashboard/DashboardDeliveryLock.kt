package com.trendyol.stove.dashboard

import java.io.Closeable
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Share the descriptor: closing another descriptor can release POSIX locks owned by this JVM. */
internal class DashboardDeliveryLock private constructor(private val path: Path, private val state: State) : Closeable {
  private val closed = AtomicBoolean(false)

  fun tryAcquire(): FileLock? = try {
    state.channel.tryLock()
  } catch (_: OverlappingFileLockException) {
    null
  }

  override fun close() {
    if (!closed.compareAndSet(false, true)) return
    channels.computeIfPresent(path) { _, current ->
      check(current === state)
      current.users--
      if (current.users == 0) {
        current.channel.close()
        null
      } else {
        current
      }
    }
  }

  private class State(val channel: FileChannel, var users: Int = 0)

  companion object {
    private val channels = ConcurrentHashMap<Path, State>()

    fun open(path: Path): DashboardDeliveryLock {
      val canonical = path.toAbsolutePath().normalize()
      val state = channels.compute(canonical) { _, existing ->
        (existing ?: State(FileChannel.open(canonical, StandardOpenOption.CREATE, StandardOpenOption.WRITE)))
          .also { it.users++ }
      }!!
      return DashboardDeliveryLock(canonical, state)
    }
  }
}
