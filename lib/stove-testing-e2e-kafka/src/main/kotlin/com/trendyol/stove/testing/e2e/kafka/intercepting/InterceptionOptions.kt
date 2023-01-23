package com.trendyol.stove.testing.e2e.kafka.intercepting

class InterceptionOptions(
    private val errorTopicSuffixes: List<String>,
) {

    fun isErrorTopic(topic: String): Boolean = errorTopicSuffixes.any { suffix -> topic.endsWith(suffix, ignoreCase = true) }
}
