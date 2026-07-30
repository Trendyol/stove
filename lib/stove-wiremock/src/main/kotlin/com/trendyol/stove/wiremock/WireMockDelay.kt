package com.trendyol.stove.wiremock

import kotlin.time.Duration

internal fun Duration.toWireMockDelayMilliseconds(): Int {
  require(!isNegative()) { "WireMock response delay cannot be negative" }
  require(!isInfinite()) { "WireMock response delay must be finite" }
  require(inWholeMilliseconds <= Int.MAX_VALUE) {
    "WireMock response delay must fit in Int milliseconds"
  }
  return inWholeMilliseconds.toInt()
}
