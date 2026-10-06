package stove.ktor.bff.tests

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import stove.ktor.oidc.server.ProofReplayCache
import stove.ktor.oidc.server.ResourceUnauthorized
import java.time.*

class ProofReplayCacheTest : FunSpec({
  test("full cache preserves live replay records and expires them before admitting new proofs") {
    val clock = ReplayClock(Instant.parse("2026-01-01T00:00:00Z"))
    val cache = ProofReplayCache(clock, capacity = 1)
    val expiry = clock.instant().plusSeconds(60)
    cache.accept("key", "proof", expiry)
    shouldThrow<ResourceUnauthorized> { cache.accept("key", "another-proof", expiry) }
    shouldThrow<ResourceUnauthorized> { cache.accept("key", "proof", expiry) }
    clock.now = expiry
    shouldThrow<ResourceUnauthorized> { cache.accept("key", "proof", expiry) }
    cache.accept("key", "new-proof", expiry.plusSeconds(60))
  }

  test("proof IDs are scoped to the signing key") {
    val clock = Clock.systemUTC()
    val cache = ProofReplayCache(clock)
    val expiry = clock.instant().plusSeconds(60)
    cache.accept("key-one", "same-id", expiry)
    cache.accept("key-two", "same-id", expiry)
    shouldThrow<ResourceUnauthorized> { cache.accept("key-one", "same-id", expiry) }
  }
})

private class ReplayClock(var now: Instant) : Clock() {
  override fun instant(): Instant = now
  override fun getZone(): ZoneId = ZoneOffset.UTC
  override fun withZone(zone: ZoneId): Clock = this
}
