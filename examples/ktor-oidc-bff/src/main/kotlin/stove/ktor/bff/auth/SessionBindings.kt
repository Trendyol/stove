package stove.ktor.bff.auth

import stove.ktor.bff.auth.storage.SessionSnapshot
import stove.ktor.oidc.client.TokenBinding
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/** Local nonce/key cache only. Authentication and token rotation always consult shared storage. */
internal class SessionBindings {
  private data class Entry(val binding: TokenBinding, val expiresAt: Instant)
  private val entries = ConcurrentHashMap<String, Entry>()

  fun get(id: String, snapshot: SessionSnapshot): TokenBinding {
    entries.entries.removeIf { !it.value.expiresAt.isAfter(snapshot.observedAt) }
    return entries.computeIfAbsent(id) {
      Entry(snapshot.session.binding.restore(), snapshot.session.expiresAt)
    }.binding
  }
}
