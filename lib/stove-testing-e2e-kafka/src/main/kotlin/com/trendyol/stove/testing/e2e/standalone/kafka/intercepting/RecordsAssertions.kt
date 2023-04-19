package com.trendyol.stove.testing.e2e.standalone.kafka.intercepting

import java.util.UUID
import java.util.concurrent.ConcurrentMap

internal interface RecordsAssertions {
    val assertions: ConcurrentMap<UUID, KafkaAssertion<*>>
}
