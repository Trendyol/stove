package stove.ktor.bff.auth

import java.time.Instant

sealed interface RefreshToken {
  data object Unavailable : RefreshToken
  data class Available(val value: String) : RefreshToken
}

data class SessionTokens(
  val accessToken: String,
  val expiresAt: Instant,
  val refreshAt: Instant,
  val refreshToken: RefreshToken
)

data class AuthenticatedTokens(val identity: VerifiedIdentity, val tokens: SessionTokens)
