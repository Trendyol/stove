package stove.ktor.bff.tests

import com.nimbusds.jwt.SignedJWT
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.*
import stove.ktor.bff.auth.*
import stove.ktor.bff.auth.storage.*
import stove.ktor.bff.fixtures.*
import stove.ktor.oidc.client.TokenBinding
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds

/** The same behavioral contract runs against a shared map and two independent PostgreSQL pools. */
fun FunSpec.sessionStorageContract(open: suspend () -> SessionStores) {
  test("a callback is consumed once across instances and an incorrect cookie does not consume it") {
    open().use { stores ->
      val first = stores.browser()
      val second = stores.browser(stores.second)
      val login = first.beginLogin()
      shouldThrow<LoginRejected> { second.finishLogin(login.state, "wrong") }
      coroutineScope {
        val outcomes = List(12) {
          async {
            try {
              second.finishLogin(login.state, login.state)
              true
            } catch (_: LoginRejected) {
              false
            }
          }
        }.awaitAll()
        outcomes.count { it } shouldBe 1
      }
    }
  }

  test("login state expires without allowing another callback") {
    open().use { stores ->
      val browser = stores.browser(policy = SessionPolicy(loginLifetime = 100.milliseconds))
      val login = browser.beginLogin()
      delay(150)
      shouldThrow<LoginRejected> { stores.browser(stores.second).finishLogin(login.state, login.state) }
    }
  }

  test("both instances restore the same DPoP key and browser identity") {
    open().use { stores ->
      val sessions = stores.create()
      sessions.first.subject shouldBe "alice"
      sessions.second.csrfToken shouldBe sessions.first.csrfToken
      val first = sessions.first.binding as TokenBinding.Dpop
      val second = sessions.second.binding as TokenBinding.Dpop
      SignedJWT.parse(first.proof("GET", "https://api.test/orders")).header.jwk.computeThumbprint() shouldBe
        SignedJWT.parse(second.proof("GET", "https://api.test/orders")).header.jwk.computeThumbprint()
    }
  }

  test("concurrent instances share one refresh and use its rotated credential next") {
    open().use { stores ->
      val sessions = stores.create()
      val entered = CompletableDeferred<Unit>()
      val release = CompletableDeferred<Unit>()
      val calls = AtomicInteger()
      coroutineScope {
        val pending = List(12) { index ->
          async {
            val session = if (index % 2 == 0) sessions.first else sessions.second
            session.accessToken { credential ->
              credential shouldBe "refresh-1"
              calls.incrementAndGet()
              entered.complete(Unit)
              release.await()
              sessionTokens("access-2", "refresh-2", refreshDue = false)
            }
          }
        }
        entered.await()
        release.complete(Unit)
        pending.awaitAll().toSet() shouldBe setOf("access-2")
      }
      calls.get() shouldBe 1
      val snapshot = checkNotNull(stores.first.readSession(sessions.id))
      stores.first.replaceSession(sessions.id, snapshot.version, snapshot.session.copy(state = SessionState.Ready(sessionTokens("access-2", "refresh-2")))) shouldBe true
      sessions.second.accessToken { credential ->
        credential shouldBe "refresh-2"
        sessionTokens("access-3", "refresh-3", refreshDue = false)
      } shouldBe "access-3"
    }
  }

  test("successful refresh keeps connections alive and logout on another instance wakes them") {
    open().use { stores ->
      val sessions = stores.create()
      coroutineScope {
        val closed = async(start = CoroutineStart.UNDISPATCHED) { sessions.first.awaitClosed() }
        sessions.second.accessToken { sessionTokens(refreshDue = false) }
        closed.isCompleted shouldBe false
        sessions.second.close()
        withTimeout(2_000) { closed.await() }
      }
    }
  }

  for (failure in listOf(LoginRequired(), ProviderUnavailable("network"), CancellationException("cancelled"))) {
    test("${failure.javaClass.simpleName} during refresh invalidates every instance") {
      open().use { stores ->
        val sessions = stores.create()
        shouldThrow<Exception> { sessions.first.accessToken { throw failure } } shouldBe failure
        shouldThrow<LoginRequired> { sessions.second.accessToken { error("must not reuse credential") } }
        withTimeout(2_000) { sessions.second.awaitClosed() }
      }
    }
  }

  test("logout wins over an in-flight refresh without waiting for the provider") {
    open().use { stores ->
      val sessions = stores.create()
      val entered = CompletableDeferred<Unit>()
      val release = CompletableDeferred<Unit>()
      coroutineScope {
        val refreshing = async {
          shouldThrow<LoginRequired> {
            sessions.first.accessToken {
              entered.complete(Unit)
              release.await()
              sessionTokens(refreshDue = false)
            }
          }
        }
        entered.await()
        withTimeout(2_000) { sessions.second.close() }
        release.complete(Unit)
        refreshing.await()
      }
      shouldThrow<LoginRequired> { stores.browser().session(sessions.id) }
    }
  }

  test("an abandoned refresh claim expires and neither retry nor late completion can resurrect it") {
    open().use { stores ->
      val sessions = stores.create()
      val snapshot = checkNotNull(stores.first.readSession(sessions.id))
      val claimed = snapshot.session.copy(state = SessionState.Refreshing(snapshot.observedAt.plusMillis(100)))
      stores.first.replaceSession(sessions.id, snapshot.version, claimed) shouldBe true
      delay(150)
      stores.second.replaceSession(sessions.id, snapshot.version + 1, snapshot.session.copy(state = SessionState.Ready(sessionTokens(refreshDue = false)))) shouldBe false
      shouldThrow<LoginRequired> { sessions.second.accessToken { error("uncertain credential must never be retried") } }
    }
  }

  test("refresh timeout invalidates the session and cancels the provider call") {
    open().use { stores ->
      val sessions = stores.create(SessionPolicy(refreshTimeout = 100.milliseconds))
      shouldThrow<TimeoutCancellationException> { sessions.first.accessToken { awaitCancellation() } }
      shouldThrow<LoginRequired> { sessions.second.ensureActive() }
    }
  }

  test("absolute expiry wakes idle connections and rejects stale replacements") {
    open().use { stores ->
      val sessions = stores.create(SessionPolicy(sessionLifetime = 300.milliseconds))
      val snapshot = checkNotNull(stores.first.readSession(sessions.id))
      withTimeout(2_000) { sessions.second.awaitClosed() }
      stores.first.replaceSession(sessions.id, snapshot.version, snapshot.session.copy(state = SessionState.Ready(sessionTokens(refreshDue = false)))) shouldBe false
      shouldThrow<LoginRequired> { sessions.first.ensureActive() }
      stores.second.removeExpired()
      stores.first.readSession(sessions.id) shouldBe null
    }
  }

  test("a session without a refresh grant stops at access-token expiry") {
    open().use { stores ->
      val tokens = sessionTokens().copy(expiresAt = java.time.Instant.now().plusMillis(300), refreshToken = RefreshToken.Unavailable)
      val sessions = stores.create(tokens = tokens)
      sessions.second.accessToken { error("no refresh credential") } shouldBe "access-1"
      withTimeout(2_000) { sessions.first.awaitClosed() }
      shouldThrow<LoginRequired> { sessions.second.ensureActive() }
    }
  }

  test("CSRF rejection preserves the session and valid logout revokes it") {
    open().use { stores ->
      val sessions = stores.create()
      val browser = stores.browser(stores.second)
      shouldThrow<CsrfRejected> { browser.logout(sessions.id, "wrong") }
      sessions.first.ensureActive()
      browser.logout(sessions.id, sessions.second.csrfToken)
      shouldThrow<LoginRequired> { sessions.first.ensureActive() }
    }
  }
}
