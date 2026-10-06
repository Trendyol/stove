package stove.ktor.bff.auth.storage

import io.ktor.server.application.Application
import io.ktor.server.application.log
import kotlinx.coroutines.*
import kotlin.time.Duration.Companion.minutes

/** Expiry checks enforce validity immediately; this only reclaims storage space. */
internal fun Application.sessionMaintenance(storage: BrowserSessionStore) = launch {
  while (isActive) {
    delay(1.minutes)
    try {
      storage.removeExpired()
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (failure: Exception) {
      log.warn("Expired session cleanup failed", failure)
    }
  }
}
