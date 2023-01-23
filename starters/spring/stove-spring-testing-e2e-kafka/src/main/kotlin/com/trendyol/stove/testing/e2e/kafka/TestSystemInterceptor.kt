package com.trendyol.stove.testing.e2e.kafka

import arrow.core.Option
import arrow.core.firstOrNone
import arrow.core.getOrElse
import arrow.core.handleErrorWith
import arrow.core.toOption
import com.fasterxml.jackson.databind.ObjectMapper
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.apache.kafka.clients.consumer.Consumer
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.clients.producer.RecordMetadata
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.kafka.listener.CompositeRecordInterceptor
import org.springframework.kafka.support.ProducerListener
import org.springframework.stereotype.Component
import kotlin.reflect.KClass
import kotlin.time.Duration

@Component
class TestSystemKafkaInterceptor(private val objectMapper: ObjectMapper) :
    CompositeRecordInterceptor<String, String>(),
    ProducerListener<String, Any> {
    data class Failure(
        val message: Any,
        val reason: Throwable,
    )

    private val logger: Logger = LoggerFactory.getLogger(javaClass)
    private val consumedRecords: ConcurrentMap<UUID, ConsumerRecord<String, String>> = ConcurrentHashMap()
    private val producedRecords: ConcurrentMap<UUID, ProducerRecord<String, Any>> = ConcurrentHashMap()
    private val exceptions: ConcurrentMap<UUID, Failure> = ConcurrentHashMap()

    override fun success(
        record: ConsumerRecord<String, String>,
        consumer: Consumer<String, String>,
    ): Unit = runBlocking {
        consumedRecords.putIfAbsent(UUID.randomUUID(), record)
        logger.info(
            """SUCCESSFULLY CONSUMED:
            Consumer: ${consumer.groupMetadata().memberId()}
            Topic: ${record.topic()}
            Record: ${record.value()}
            Key: ${record.key()}
            Headers: ${record.headers().map { Pair(it.key(), String(it.value())) }}
            TestCase: ${record.headers().firstOrNone { it.key() == "testCase" }.map { String(it.value()) }.getOrElse { "" }}
            """.trimIndent()
        )
    }

    override fun onSuccess(
        record: ProducerRecord<String, Any>,
        recordMetadata: RecordMetadata,
    ): Unit = runBlocking {
        producedRecords.putIfAbsent(UUID.randomUUID(), record)
        logger.info(
            """SUCCESSFULLY PUBLISHED:
            Topic: ${record.topic()}
            Record: ${record.value()}
            Key: ${record.key()}
            Headers: ${record.headers().map { Pair(it.key(), String(it.value())) }}
            TestCase: ${record.headers().firstOrNone { it.key() == "testCase" }.map { String(it.value()) }.getOrElse { "" }}
            """.trimIndent()
        )
    }

    override fun onError(
        record: ProducerRecord<String, Any>,
        recordMetadata: RecordMetadata?,
        exception: Exception,
    ): Unit = runBlocking {
        exceptions.putIfAbsent(UUID.randomUUID(), Failure(record.value(), exception))
        logger.error(
            """PRODUCER GOT AN ERROR:
            Topic: ${record.topic()}
            Record: ${record.value()}
            Key: ${record.key()}
            Headers: ${record.headers().map { Pair(it.key(), String(it.value())) }}
            TestCase: ${record.headers().firstOrNone { it.key() == "testCase" }.map { String(it.value()) }.getOrElse { "" }}
            Exception: $exception
            """.trimIndent()
        )
    }

    override fun failure(
        record: ConsumerRecord<String, String>,
        exception: Exception,
        consumer: Consumer<String, String>,
    ): Unit = runBlocking {
        exceptions.putIfAbsent(UUID.randomUUID(), Failure(record.value(), exception))
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

    suspend fun <T : Any> waitUntilConsumed(
        atLeastIn: Duration,
        clazz: KClass<T>,
        condition: (Option<T>) -> Boolean,
    ) {
        val getRecords = { consumedRecords.map { it.value.value() } }
        getRecords.waitUntilConditionMet(atLeastIn, "While CONSUMING ${clazz.java.simpleName}") {
            val outcome = readCatching(it, clazz)
            outcome.isSuccess && condition(outcome.getOrNull().toOption())
        }

        throwIfFailed(clazz, condition)
    }

    suspend fun <T : Any> waitUntilPublished(
        atLeastIn: Duration,
        clazz: KClass<T>,
        condition: (Option<T>) -> Boolean,
    ) {
        val getRecords = { producedRecords.map { it.value.value() } }
        getRecords.waitUntilConditionMet(atLeastIn, "While PUBLISHING ${clazz.java.simpleName}") {
            val outcome = readCatching(it.toString(), clazz)
            outcome.isSuccess && condition(outcome.getOrNull().toOption())
        }

        throwIfFailed(clazz, condition)
    }

    private fun <T : Any> readCatching(
        json: String,
        clazz: KClass<T>,
    ) = runCatching { objectMapper.readValue(json, clazz.java) }

    fun reset() {
        exceptions.clear()
        producedRecords.clear()
        consumedRecords.clear()
    }

    private fun <T : Any> throwIfFailed(
        clazz: KClass<T>,
        selector: (Option<T>) -> Boolean,
    ) = exceptions
        .filter { selector(readCatching(it.value.message.toString(), clazz).getOrNull().toOption()) }
        .forEach { throw it.value.reason }

    private suspend fun <T> (() -> Collection<T>).waitUntilConditionMet(
        duration: Duration,
        subject: String,
        condition: (T) -> Boolean,
    ): Collection<T> = runCatching {
        val collectionFunc = this
        withTimeout(duration) { while (!collectionFunc().any { condition(it) }) delay(50) }
        return collectionFunc().filter { condition(it) }
    }.handleErrorWith {
        when (it) {
            is TimeoutCancellationException -> throw AssertionError("GOT A TIMEOUT: $subject. ${dumpMessages()}")
            is ConcurrentModificationException ->
                Result.success(waitUntilConditionMet(duration, subject, condition))

            else -> throw it
        }
    }.getOrThrow()

    private fun dumpMessages(): String = """Messages in the KafkaSystem so far:
        PUBLISHED MESSAGES: 
        ${producedRecords.map { it.value.value() }.joinToString("\n")}
        ------------------------
        CONSUMED MESSAGES: 
        ${consumedRecords.map { it.value.value() }.joinToString("\n")}
    """.trimIndent()
}
