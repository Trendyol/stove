package stove.ktor.oidc.server

import java.time.Clock
import java.time.Instant
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Implementations must atomically reject a live duplicate across every instance sharing the store. */
fun interface ProofReplayStore {
  suspend fun accept(thumbprint: String, id: String, expiresAt: Instant)
}

/** Atomic, bounded replay detection. Live entries are never evicted to admit another proof. */
class ProofReplayCache(private val clock: Clock = Clock.systemUTC(), private val capacity: Int = 10_000) : ProofReplayStore {
  private val lock = ReentrantLock()
  private val accepted = mutableMapOf<ProofId, Instant>()

  init {
    require(capacity > 0)
  }

  override suspend fun accept(thumbprint: String, id: String, expiresAt: Instant): Unit = lock.withLock {
    val now = clock.instant()
    accepted.entries.removeIf { !it.value.isAfter(now) }
    val proof = ProofId(thumbprint, id)
    if (!expiresAt.isAfter(now)) throw invalidProof("Proof expired")
    if (proof in accepted) throw invalidProof("Proof already used")
    if (accepted.size >= capacity) throw invalidProof("Proof replay cache is full")
    accepted[proof] = expiresAt
  }

  private data class ProofId(val thumbprint: String, val id: String)
}
