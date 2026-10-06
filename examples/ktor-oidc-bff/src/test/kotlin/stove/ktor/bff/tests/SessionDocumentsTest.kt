package stove.ktor.bff.tests

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*
import stove.ktor.bff.auth.RefreshToken
import stove.ktor.bff.auth.storage.SessionState
import stove.ktor.bff.auth.storage.postgres.*
import stove.ktor.bff.fixtures.LegacySessionDocuments

class SessionDocumentsTest : FunSpec({
  for (name in listOf("login-bearer", "login-dpop")) {
    test("$name keeps the version-one format through domain conversion") {
      val legacy = LegacySessionDocuments.read(name)
      val login = sessionJson.decodeFromString(LoginDocument.serializer(), legacy).toLogin()
      val encoded = sessionJson.encodeToString(LoginDocument.serializer(), LoginDocument.from(login))
      Json.parseToJsonElement(encoded) shouldBe Json.parseToJsonElement(legacy)
    }
  }

  for (name in listOf("session-ready", "session-no-refresh", "session-refreshing")) {
    test("$name keeps the version-one format through domain conversion") {
      val legacy = LegacySessionDocuments.read(name)
      val session = sessionJson.decodeFromString(SessionDocument.serializer(), legacy).toSession()
      val encoded = sessionJson.encodeToString(SessionDocument.serializer(), SessionDocument.from(session))
      Json.parseToJsonElement(encoded) shouldBe Json.parseToJsonElement(legacy)
    }
  }

  test("an omitted refresh credential means no refresh grant") {
    val session = sessionJson.decodeFromString(SessionDocument.serializer(), LegacySessionDocuments.read("session-no-refresh")).toSession()
    (session.state as SessionState.Ready).tokens.refreshToken shouldBe RefreshToken.Unavailable
  }

  for (name in listOf("login-bearer", "session-ready")) {
    val original = Json.parseToJsonElement(LegacySessionDocuments.read(name)).jsonObject
    val serializer = if (name.startsWith("login")) LoginDocument.serializer() else SessionDocument.serializer()

    test("$name rejects unknown format versions") {
      val unsupported = JsonObject(original + ("format" to JsonPrimitive(2)))
      shouldThrow<IllegalArgumentException> { sessionJson.decodeFromJsonElement(serializer, unsupported) }
    }

    test("$name requires an explicit format version") {
      shouldThrow<SerializationException> { sessionJson.decodeFromJsonElement(serializer, JsonObject(original - "format")) }
    }

    test("$name tolerates additional fields from a compatible writer") {
      sessionJson.decodeFromJsonElement(serializer, JsonObject(original + ("additionalField" to JsonPrimitive("value")))) shouldBe
        sessionJson.decodeFromJsonElement(serializer, original)
    }
  }

  for (field in listOf("state", "binding")) {
    test("an unknown $field discriminator cannot become an authenticated session") {
      val original = Json.parseToJsonElement(LegacySessionDocuments.read("session-ready")).jsonObject
      val unsupported = JsonObject(original + (field to buildJsonObject { put("type", "unknown") }))
      shouldThrow<SerializationException> { sessionJson.decodeFromJsonElement(SessionDocument.serializer(), unsupported) }
    }
  }
})
