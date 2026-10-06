package stove.ktor.bff.tests

import arrow.core.None
import com.nimbusds.jose.jwk.ECKey
import com.trendyol.stove.http.*
import com.trendyol.stove.postgres.postgresql
import com.trendyol.stove.system.stove
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import kotliquery.param
import stove.ktor.bff.StoveConfig
import stove.ktor.bff.auth.LoginRequired
import stove.ktor.bff.config.SessionStorageConfiguration
import stove.ktor.bff.fixtures.*
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

class PostgresPersistenceTest : FunSpec({
  test("a version-one session written before the serializer change still serves its browser") {
    stove {
      val id = UUID.randomUUID().toString()
      val cookie = "bff_session=$id"
      postgresql {
        shouldExecute(
          """
          INSERT INTO bff_sessions (namespace, id, version, payload, expires_at)
          VALUES (?, ?, 0, ?::jsonb, '2099-01-02T03:04:05.123456789Z'::timestamptz)
          """.trimIndent(),
          parameters = listOf("stove-bff".param(), id.param(), LegacySessionDocuments.read("session-ready").param())
        )
      }
      val session = BrowserFlow(this).session(cookie)
      session.subject shouldBe "legacy-user"
      session.csrfToken shouldBe "legacy-csrf"
      http {
        postAndExpectBodilessResponse(
          "/auth/logout",
          None,
          headers = mapOf("Cookie" to cookie, "Origin" to StoveConfig.origin, "X-CSRF-Token" to session.csrfToken)
        ) { it.status shouldBe 204 }
      }
      postgresql { shouldHaveNoSession(cookie) }
    }
  }

  test("Flyway installs the versioned JSONB schema and composite keys before the BFF serves requests") {
    stove {
      postgresql {
        shouldQuery(
          "SELECT script, success AND checksum IS NOT NULL AS verified FROM bff_schema_history WHERE version = ?",
          parameters = listOf("1".param()),
          mapper = { it.string("script") to it.boolean("verified") }
        ) { it shouldBe listOf("V1__create_browser_sessions.sql" to true) }
        shouldQuery(
          """
          SELECT table_name, column_name, data_type FROM information_schema.columns
          WHERE table_schema = 'public' AND table_name IN ('bff_sessions', 'bff_login_attempts')
            AND column_name IN ('payload', 'expires_at')
          """.trimIndent(),
          mapper = { Triple(it.string("table_name"), it.string("column_name"), it.string("data_type")) }
        ) { rows ->
          rows.toSet() shouldBe setOf(
            Triple("bff_sessions", "payload", "jsonb"),
            Triple("bff_login_attempts", "payload", "jsonb"),
            Triple("bff_sessions", "expires_at", "timestamp with time zone"),
            Triple("bff_login_attempts", "expires_at", "timestamp with time zone")
          )
        }
        shouldQuery(
          """
          SELECT conrelid::regclass::text AS table_name, pg_get_constraintdef(oid) AS definition
          FROM pg_constraint WHERE contype = 'p' AND conrelid IN ('bff_sessions'::regclass, 'bff_login_attempts'::regclass)
          """.trimIndent(),
          mapper = { it.string("table_name") to it.string("definition") }
        ) { it.toSet() shouldBe setOf("bff_sessions" to "PRIMARY KEY (namespace, id)", "bff_login_attempts" to "PRIMARY KEY (namespace, id)") }
      }
    }
  }

  test("HTTP login persists PKCE and binding state, consumes it once, and logout deletes the session") {
    stove {
      val browser = BrowserFlow(this)
      val login = browser.begin()
      val state = login.url.query().getValue("state")
      lateinit var persistedLogin: JsonObject
      postgresql {
        persistedLogin = loginPayload(state)
        persistedLogin.text("state") shouldBe state
        persistedLogin.text("nonce") shouldBe login.url.query().getValue("nonce")
        val challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
          MessageDigest.getInstance("SHA-256").digest(persistedLogin.text("verifier").toByteArray(Charsets.US_ASCII))
        )
        challenge shouldBe login.url.query().getValue("code_challenge")
      }
      val callback = browser.authorize(login)
      browser.callback(callback, "bff_login=wrong", expectedStatus = 400)
      postgresql { loginPayload(state) shouldBe persistedLogin }
      val response = browser.callback(callback, login.cookie)
      val cookie = response.headerValues("Set-Cookie").single { it.startsWith("bff_session=") }.substringBefore(';')
      val session = browser.session(cookie)
      postgresql {
        shouldHaveNoLogin(state)
        val row = sessionRow(cookie)
        row.version shouldBe 0
        row.payload.text("subject") shouldBe session.subject
        row.payload.text("csrfToken") shouldBe session.csrfToken
        row.payload.text("nonce") shouldBe persistedLogin.text("nonce")
        row.state.text("type") shouldBe "ready"
        row.state.text("accessToken").isNotBlank() shouldBe true
        row.state.text("refreshToken").isNotBlank() shouldBe true
        row.binding shouldBe persistedLogin.getValue("binding").jsonObject
        if (StoveConfig.keycloak) ECKey.parse(row.binding.text("privateJwk")).isPrivate shouldBe true
      }
      browser.callback(callback, login.cookie, expectedStatus = 400)
      http {
        postAndExpectBodilessResponse(
          "/auth/logout",
          None,
          headers = mapOf("Cookie" to cookie, "Origin" to StoveConfig.origin, "X-CSRF-Token" to session.csrfToken)
        ) { it.status shouldBe 204 }
        getBodilessResponse("/api/session", headers = mapOf("Cookie" to cookie)) { it.status shouldBe 401 }
      }
      postgresql { shouldHaveNoSession(cookie) }
    }
  }

  test("logout deletes only its session, preserving other browsers and namespaces") {
    stove {
      val browser = BrowserFlow(this)
      val cookie = browser.signIn()
      val otherCookie = browser.signIn()
      val otherBrowser = browser.session(otherCookie)
      val session = browser.session(cookie)
      val namespace = UUID.randomUUID().toString()
      postgresql { copySessionToNamespace(cookie, namespace) }
      http {
        postAndExpectBodilessResponse(
          "/auth/logout",
          None,
          headers = mapOf("Cookie" to cookie, "Origin" to StoveConfig.origin, "X-CSRF-Token" to session.csrfToken)
        ) { it.status shouldBe 204 }
      }
      browser.session(otherCookie) shouldBe otherBrowser
      postgresql {
        shouldHaveNoSession(cookie)
        sessionRow(cookie, namespace).payload.text("subject") shouldBe session.subject
        shouldExecute("DELETE FROM bff_sessions WHERE namespace = ?", parameters = listOf(namespace.param()))
      }
    }
  }

  test("expired persisted login and session records are rejected and cleanup removes only their namespace") {
    stove {
      val browser = BrowserFlow(this)
      val cookie = browser.signIn()
      val login = browser.begin()
      val state = login.url.query().getValue("state")
      val callback = browser.authorize(login)
      val abandonedLogin = browser.begin().url.query().getValue("state")
      val otherNamespace = "isolation-$state"
      postgresql {
        copySessionToNamespace(cookie, otherNamespace)
        shouldExecute(
          "UPDATE bff_sessions SET expires_at = clock_timestamp() - interval '1 second' WHERE id = ?",
          parameters = listOf(cookie.substringAfter('=').param())
        )
        shouldExecute(
          "UPDATE bff_login_attempts SET expires_at = clock_timestamp() - interval '1 second' WHERE namespace = ? AND id IN (?, ?)",
          parameters = listOf("stove-bff".param(), state.param(), abandonedLogin.param())
        )
      }
      browser.callback(callback, login.cookie, expectedStatus = 400)
      http { getBodilessResponse("/api/session", headers = mapOf("Cookie" to cookie)) { it.status shouldBe 401 } }
      // Exercise the same cleanup operation used by the scheduled job without waiting for its one-minute tick.
      SessionStorageConfiguration.load(StoveConfig.postgresSettings).open().use { it.removeExpired() }
      postgresql {
        shouldHaveNoLogin(state)
        shouldHaveNoLogin(abandonedLogin)
        shouldHaveNoSession(cookie)
        sessionRow(cookie, otherNamespace).version shouldBe 0
        shouldExecute("DELETE FROM bff_sessions WHERE namespace = ?", parameters = listOf(otherNamespace.param()))
      }
    }
  }

  test("database outage returns 503 without clearing the cookie and the same session recovers") {
    stove {
      val browser = BrowserFlow(this)
      val cookie = browser.signIn()
      val before = browser.session(cookie)
      postgresql { pause() }
      try {
        http {
          getBodilessResponse("/api/session", headers = mapOf("Cookie" to cookie)) {
            it.status shouldBe 503
            it.headers.keys.none { name -> name.equals("Set-Cookie", ignoreCase = true) } shouldBe true
          }
        }
      } finally {
        withContext(NonCancellable) { postgresql { unpause() } }
      }
      browser.session(cookie) shouldBe before
      postgresql { sessionRow(cookie).payload.text("subject") shouldBe before.subject }
    }
  }

  test("refresh ownership removes reusable credentials from SQL and logout prevents late completion") {
    stove {
      val namespace = UUID.randomUUID().toString()
      SessionStores.postgres(namespace).use { stores ->
        val sessions = stores.create()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        withTimeout(10_000) {
          val refreshing = async {
            shouldThrow<LoginRequired> {
              sessions.first.accessToken {
                entered.complete(Unit)
                release.await()
                sessionTokens(refreshDue = false)
              }
            }
          }
          withTimeout(5_000) { entered.await() }
          postgresql {
            val claimed = sessionRow(sessions.id, namespace)
            claimed.version shouldBe 1
            claimed.state.keys shouldBe setOf("type", "deadline")
            claimed.state.text("type") shouldBe "refreshing"
          }
          sessions.second.close()
          postgresql { shouldHaveNoSession(sessions.id, namespace) }
          release.complete(Unit)
          refreshing.await()
        }
        postgresql { shouldHaveNoSession(sessions.id, namespace) }
      }
    }
  }
})
