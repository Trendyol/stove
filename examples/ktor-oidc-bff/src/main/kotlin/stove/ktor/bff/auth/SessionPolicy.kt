package stove.ktor.bff.auth

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

data class SessionPolicy(
  val loginLifetime: Duration = 5.minutes,
  val sessionLifetime: Duration = 30.minutes,
  val refreshTimeout: Duration = 15.seconds,
  val refreshPollInterval: Duration = 100.milliseconds,
  val revocationPollInterval: Duration = 1.seconds
) {
  init {
    require(loginLifetime in 1.milliseconds..5.minutes)
    require(sessionLifetime in 1.milliseconds..30.minutes)
    require(refreshTimeout in 1.milliseconds..1.minutes)
    require(refreshPollInterval in 1.milliseconds..refreshTimeout)
    require(revocationPollInterval in 1.milliseconds..1.minutes)
  }
}
