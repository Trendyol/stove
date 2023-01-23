package com.trendyol.stove.testing.e2e.kafka.intercepting

import arrow.core.Option
import arrow.core.firstOrNone
import arrow.core.getOrElse
import arrow.core.toOption
import java.util.UUID
import java.util.concurrent.ConcurrentMap
import kotlinx.coroutines.runBlocking
import org.apache.kafka.clients.consumer.Consumer
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.Logger
import kotlin.reflect.KClass
import kotlin.time.Duration

internal interface ConsumingOps : CommonOps {
    val logger: Logger
    val consumedRecords: ConcurrentMap<UUID, ConsumerRecord<String, String>>

    suspend fun <T : Any> waitUntilConsumed(
        atLeastIn: Duration,
        clazz: KClass<T>,
        condition: (Option<T>) -> Boolean,
    ) {
        assertions.putIfAbsent(UUID.randomUUID(), KafkaAssertion(clazz, condition))
        val getRecords = { consumedRecords.map { it.value.value() } }
        getRecords.waitUntilConditionMet(atLeastIn, "While CONSUMING ${clazz.java.simpleName}") {
            val outcome = readCatching(it, clazz)
            outcome.isSuccess && condition(outcome.getOrNull().toOption())
        }

        throwIfFailed(clazz, condition)
    }

    fun recordMessage(
        record: ConsumerRecord<String, String>,
        consumer: Consumer<String, String>,
    ): Unit = runBlocking {
        consumedRecords.putIfAbsent(UUID.randomUUID(), record)
        logger.info(
            """RECEIVED MESSAGE:
            Consumer: ${consumer.groupMetadata().memberId()} | ${consumer.groupMetadata().groupId()}
            Topic: ${record.topic()}
            Record: ${record.value()}
            Key: ${record.key()}
            Headers: ${record.headers().map { Pair(it.key(), String(it.value())) }}
            TestCase: ${record.headers().firstOrNone { it.key() == "testCase" }.map { String(it.value()) }.getOrElse { "" }}
            """.trimIndent()
        )
    }

    fun recordError(
        record: ConsumerRecord<String, String>,
    ): Unit = runBlocking {
        val exception = AssertionError(buildErrorMessage(record))
        exceptions.putIfAbsent(UUID.randomUUID(), Failure(record.topic(), record.value(), exception))
        logger.error(
            """CONSUMER GOT AN ERROR:
            Topic: ${record.topic()}
            Record: ${record.value()}
            Key: ${record.key()}
            Headers: ${record.headers().map { Pair(it.key(), String(it.value())) }}
            TestCase: ${record.headers().firstOrNone { it.key() == "testCase" }.map { String(it.value()) }.getOrElse { "" }}
            Exception: $exception
            """.trimIndent()
        )
    }

    private fun buildErrorMessage(record: ConsumerRecord<String, String>): String = """MESSAGE FAILED TO CONSUME:
        Topic: ${record.topic()}
        Record: ${record.value()}
        Key: ${record.key()}
        Headers: ${record.headers().map { Pair(it.key(), String(it.value())) }}
    """.trimIndent()

    override fun dumpMessages(): String {
        return "TODO://CONSUMED MESSAGES"
    }
}
