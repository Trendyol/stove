package com.trendyol.stove.testing.e2e.ktor

import java.time.Instant

/**
 * Common test domain classes for Ktor bridge tests.
 */

fun interface GetUtcNow {
  companion object {
    val frozenTime: Instant = Instant.parse("2021-01-01T00:00:00Z")
  }

  operator fun invoke(): Instant
}

class SystemTimeGetUtcNow : GetUtcNow {
  override fun invoke(): Instant = GetUtcNow.frozenTime
}

class ExampleService(
  private val getUtcNow: GetUtcNow
) {
  fun whatIsTheTime(): Instant = getUtcNow()
}

data class TestConfig(
  val message: String = "Hello from Stove!"
)
