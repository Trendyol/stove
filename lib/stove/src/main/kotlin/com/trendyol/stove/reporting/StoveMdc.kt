package com.trendyol.stove.reporting

import org.slf4j.MDC

object StoveMdc {
  const val TEST_ID_KEY = "stove.test.id"
  const val TEST_NAME_KEY = "stove.test.name"
  const val SPEC_NAME_KEY = "stove.spec.name"

  fun values(ctx: StoveTestContext): Map<String, String> = buildMap {
    put(TEST_ID_KEY, ctx.testId)
    put(TEST_NAME_KEY, ctx.testName)
    ctx.specName?.takeIf(String::isNotBlank)?.let { put(SPEC_NAME_KEY, it) }
  }

  fun mergedValues(ctx: StoveTestContext): Map<String, String> =
    (MDC.getCopyOfContextMap() ?: emptyMap()) + values(ctx)

  fun install(ctx: StoveTestContext): Map<String, String>? {
    val previous = MDC.getCopyOfContextMap()
    values(ctx).forEach { (key, value) -> MDC.put(key, value) }
    return previous
  }

  fun restore(previous: Map<String, String>?) {
    if (previous == null) {
      MDC.clear()
    } else {
      MDC.setContextMap(previous)
    }
  }
}
