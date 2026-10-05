package com.trendyol.stove.oidc

import com.trendyol.stove.reporting.ReportEventListener
import com.trendyol.stove.reporting.StoveTestContext
import com.trendyol.stove.scoping.TestScopeCleanupListener

internal class MockTestLifecycle(private val rules: MockRules) : ReportEventListener {
  private val cleanup = TestScopeCleanupListener { owner -> rules.calls.clear(owner) }

  override fun onTestStarted(ctx: StoveTestContext) {
    cleanup.onTestStarted(ctx)
    rules.remove(ctx.testId)
    rules.calls.startTest(ctx.testId)
    rules.calls.pruneUntaggedOutsideWindows()
  }

  override fun onTestEnded(testId: String) {
    rules.remove(testId)
    rules.calls.endTest(testId)
    cleanup.onTestEnded(testId)
  }
}
