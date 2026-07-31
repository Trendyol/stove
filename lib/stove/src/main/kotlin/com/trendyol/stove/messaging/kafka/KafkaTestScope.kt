package com.trendyol.stove.messaging.kafka

import com.trendyol.stove.scoping.belongsToTest as scopedBelongsToTest
import com.trendyol.stove.scoping.stoveTestId as scopedStoveTestId

/** A record is excluded only when its headers prove that it belongs to another test. */
fun Map<String, *>.belongsToTest(testId: String?): Boolean = scopedBelongsToTest(testId)

/** Extracts Stove's test id from its explicit header or W3C baggage. */
fun Map<String, *>.stoveTestId(): String? = scopedStoveTestId()
