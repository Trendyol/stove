package stove.ktor.bff.tests

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.*
import stove.ktor.bff.auth.*
import stove.ktor.oidc.client.TokenBinding
import java.time.*

class SessionTokensTest : FunSpec({
  test("token responses rotate refresh credentials or retain an omitted credential") {
    val clock = Clock.systemUTC()
    val previous = RefreshToken.Available("original")
    val response = buildJsonObject {
      put("access_token", "access")
      put("token_type", "DPoP")
      put("expires_in", 300)
    }
    val binding = TokenBinding.Dpop()
    response.sessionTokens(binding, clock.instant(), previous).refreshToken shouldBe previous
    val rotated = JsonObject(response + ("refresh_token" to JsonPrimitive("rotated")))
    rotated.sessionTokens(binding, clock.instant(), previous).refreshToken shouldBe RefreshToken.Available("rotated")
  }

  test("token responses reject a binding mismatch in either direction") {
    val now = Instant.parse("2026-01-01T00:00:00Z")
    val response = buildJsonObject {
      put("access_token", "access")
      put("token_type", "DPoP")
      put("expires_in", 300)
    }
    shouldThrow<LoginRejected> { response.sessionTokens(TokenBinding.Bearer, now) }
    val downgraded = JsonObject(response + ("token_type" to JsonPrimitive("Bearer")))
    shouldThrow<LoginRejected> { downgraded.sessionTokens(TokenBinding.Dpop(), now) }
  }
})
