package stove.ktor.bff.fixtures

import com.trendyol.stove.postgres.PostgresqlSystem
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.*
import kotliquery.param

/** Independent SQL observations: deliberately does not deserialize through the application's codec. */
data class SessionRow(val version: Long, val deadline: String, val payload: JsonObject) {
  val state: JsonObject get() = payload.getValue("state").jsonObject
  val binding: JsonObject get() = payload.getValue("binding").jsonObject
}

suspend fun PostgresqlSystem.sessionRow(cookie: String, namespace: String = "stove-bff"): SessionRow {
  lateinit var result: SessionRow
  shouldQuery(
    "SELECT version, expires_at::text AS deadline, payload::text FROM bff_sessions WHERE namespace = ? AND id = ?",
    parameters = listOf(namespace.param(), cookie.substringAfter('=').param()),
    mapper = { SessionRow(it.long("version"), it.string("deadline"), Json.parseToJsonElement(it.string("payload")).jsonObject) }
  ) {
    it shouldHaveSize 1
    result = it.single()
  }
  return result
}

suspend fun PostgresqlSystem.loginPayload(state: String): JsonObject {
  lateinit var result: JsonObject
  shouldQuery(
    "SELECT payload::text, expires_at > clock_timestamp() AS live FROM bff_login_attempts WHERE namespace = ? AND id = ?",
    parameters = listOf("stove-bff".param(), state.param()),
    mapper = { Json.parseToJsonElement(it.string("payload")).jsonObject to it.boolean("live") }
  ) {
    it shouldHaveSize 1
    it.single().second shouldBe true
    result = it.single().first
  }
  return result
}

suspend fun PostgresqlSystem.shouldHaveNoLogin(state: String) {
  shouldQuery(
    "SELECT id FROM bff_login_attempts WHERE namespace = ? AND id = ?",
    parameters = listOf("stove-bff".param(), state.param()),
    mapper = { it.string("id") }
  ) { it shouldBe emptyList() }
}

suspend fun PostgresqlSystem.shouldHaveNoSession(cookie: String, namespace: String = "stove-bff") {
  shouldQuery(
    "SELECT id FROM bff_sessions WHERE namespace = ? AND id = ?",
    parameters = listOf(namespace.param(), cookie.substringAfter('=').param()),
    mapper = { it.string("id") }
  ) { it shouldBe emptyList() }
}

suspend fun PostgresqlSystem.makeRefreshDue(cookie: String) {
  shouldExecute(
    """
    UPDATE bff_sessions
    SET payload = jsonb_set(payload, '{state,refreshAt}', to_jsonb('2000-01-01T00:00:00Z'::text))
    WHERE namespace = ? AND id = ?
    """.trimIndent(),
    parameters = listOf("stove-bff".param(), cookie.substringAfter('=').param())
  )
}

fun JsonObject.text(name: String): String = getValue(name).jsonPrimitive.content

/** Copying a real row makes namespace isolation observable without the application's serializer. */
suspend fun PostgresqlSystem.copySessionToNamespace(cookie: String, namespace: String) {
  shouldExecute(
    """
    INSERT INTO bff_sessions (namespace, id, version, payload, expires_at)
    SELECT ?, id, version, payload, expires_at FROM bff_sessions WHERE namespace = ? AND id = ?
    """.trimIndent(),
    parameters = listOf(namespace.param(), "stove-bff".param(), cookie.substringAfter('=').param())
  )
  sessionRow(cookie, namespace).version shouldBe 0
}
