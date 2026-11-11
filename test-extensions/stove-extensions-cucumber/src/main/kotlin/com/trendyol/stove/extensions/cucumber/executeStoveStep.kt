package com.trendyol.stove.extensions.cucumber

import kotlinx.coroutines.runBlocking

/** The synchronous Cucumber worker waits for the step and all of its structured coroutines. */
internal fun executeStoveStep(body: suspend () -> Unit) {
  val session = StoveCucumberPlugin.current().session
  runBlocking { session.withContext(body) }
}
