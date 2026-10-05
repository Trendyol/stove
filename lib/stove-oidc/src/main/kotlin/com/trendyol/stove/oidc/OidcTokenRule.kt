package com.trendyol.stove.oidc

import arrow.core.Some

/** Owns a mock registration and verifies only calls routed to that rule in the current test. */
class OidcTokenRule internal constructor(
  private val system: OidcSystem,
  private val rules: MockRules,
  private val id: Long
) : AutoCloseable {
  fun calls(): List<OidcTokenCall> = evidence().filter(::matches)

  /** Point-in-time verification. Await asynchronous application work before verifying. */
  suspend fun shouldHaveBeenCalled(times: Int = 1) {
    val evidence = evidence()
    verifyTokenCalls(system, times, evidence.count(::matches), "rule #$id", evidence)
  }

  override fun close() = rules.remove(id)

  override fun toString(): String = "OidcTokenRule(#$id)"

  private fun evidence(): List<OidcTokenCall> = rules.calls.entriesWithinTest(system.reporter.currentTestId())
  private fun matches(call: OidcTokenCall): Boolean = call.disposition == OidcTokenDisposition.Matched(id)
}

internal suspend fun verifyTokenCalls(
  system: OidcSystem,
  expected: Int,
  observed: Int,
  target: String,
  evidence: List<OidcTokenCall>
) {
  requireConfiguration(expected >= 0, "times", "Expected call count must be non-negative")
  system.report(action = "verify mock token calls ($target)", output = Some("verified")) {
    check(observed == expected) {
      "Expected $expected OIDC token calls for $target, observed $observed. " +
        "Routing: ${evidence.groupingBy { it.disposition }.eachCount()}. ${ambiguityGuidance(evidence)}"
    }
  }
}

internal fun ambiguityGuidance(calls: List<OidcTokenCall>): String =
  if (calls.any { it.disposition == OidcTokenDisposition.AmbiguousTestOwnership }) {
    "Multiple active tests match an untagged request; use unique request discriminators or separate keyed providers."
  } else {
    ""
  }
