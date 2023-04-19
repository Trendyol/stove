package com.trendyol.stove.testing.e2e.standalone.kafka.intercepting

data class Failure(
    val topic: String,
    val message: Any,
    val reason: Throwable,
)
