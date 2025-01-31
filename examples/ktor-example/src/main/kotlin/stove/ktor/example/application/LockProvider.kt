package stove.ktor.example.application

import kotlinx.coroutines.sync.Mutex
import java.time.Duration

interface LockProvider {
  suspend fun acquireLock(
    name: String,
    duration: Duration
  ): Boolean

  suspend fun releaseLock(name: String)
}

class MutexLockProvider : LockProvider {
  private val mutex = Mutex()

  override suspend fun acquireLock(
    name: String,
    duration: Duration
  ): Boolean = mutex.tryLock(this)

  override suspend fun releaseLock(name: String) {
    mutex.unlock(this)
  }
}
