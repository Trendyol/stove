package com.trendyol.stove.testing.e2e.kafka

import arrow.core.*
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.*
import org.apache.kafka.clients.consumer.*
import org.apache.kafka.clients.producer.*
import org.slf4j.*
import org.springframework.kafka.listener.*
import org.springframework.kafka.support.ProducerListener
import org.springframework.stereotype.Component
import java.util.*
import java.util.concurrent.*
import kotlin.reflect.KClass
import kotlin.time.Duration

@Component
class TestSystemKafkaInterceptor(private val objectMapper: ObjectMapper) :
    CompositeRecordInterceptor<String, String>(),
    ProducerListener<String, Any> {
    private val logger: Logger = LoggerFactory.getLogger(javaClass)
    private val consumedRecords: ConcurrentMap<UUID, ConsumerRecord<String, String>> = ConcurrentHashMap()
    private val producedRecords: ConcurrentMap<UUID, ProducerRecord<String, Any>> = ConcurrentHashMap()
    private val exceptions: ConcurrentMap<UUID, Failure<Any>> = ConcurrentHashMap()

    override fun success(
        record: ConsumerRecord<String, String>,
        consumer: Consumer<String, String>
    ): Unit = runBlocking {
        consumedRecords.putIfAbsent(UUID.randomUUID(), record)
        logger.info(
            """
                SUCCESSFULLY CONSUMED:
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
        recordMetadata: RecordMetadata
    ): Unit = runBlocking {
        producedRecords.putIfAbsent(UUID.randomUUID(), record)
        logger.info(
            """
                SUCCESSFULLY PUBLISHED:
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
        exception: Exception
    ): Unit = runBlocking {
        exceptions.putIfAbsent(
            UUID.randomUUID(),
            Failure(
                ObservedMessage(record.value().toString(), record.toMetadata()),
                extractCause(exception)
            )
        )
        logger.error(
            """
                PRODUCER GOT AN ERROR:
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
        consumer: Consumer<String, String>
    ): Unit = runBlocking {
        exceptions.putIfAbsent(
            UUID.randomUUID(),
            Failure(
                ObservedMessage(record.value().toString(), record.toMetadata()),
                extractCause(exception)
            )
        )
        logger.error(
            """
                CONSUMER GOT AN ERROR:
                Topic: ${record.topic()}
                Record: ${record.value()}
                Key: ${record.key()}
                Headers: ${record.headers().map { Pair(it.key(), String(it.value())) }}
                TestCase: ${record.headers().firstOrNone { it.key() == "testCase" }.map { String(it.value()) }.getOrElse { "" }}
                Exception: $exception
            """.trimIndent()
        )
    }

    internal suspend fun <T : Any> waitUntilConsumed(
        atLeastIn: Duration,
        clazz: KClass<T>,
        condition: (metadata: ParsedMessage<T>) -> Boolean
    ) {
        val getRecords = { consumedRecords.map { it.value } }
        getRecords.waitUntilConditionMet(atLeastIn, "While CONSUMING ${clazz.java.simpleName}") {
            val outcome = readCatching(it.value(), clazz)
            outcome.isSuccess && condition(ParsedMessage(outcome.getOrNull().toOption(), it.toMetadata()))
        }

        throwIfFailed(clazz, condition)
    }

    internal suspend fun <T : Any> waitUntilFailed(
        atLeastIn: Duration,
        clazz: KClass<T>,
        condition: (metadata: FailedParsedMessage<T>) -> Boolean
    ) {
        val getRecords = { exceptions.map { it.value } }
        getRecords.waitUntilConditionMet(atLeastIn, "While WAITING FOR FAILURE ${clazz.java.simpleName}") {
            val outcome = readCatching(it.message.actual.toString(), clazz)
            outcome.isSuccess &&
                condition(
                    FailedParsedMessage(
                        ParsedMessage(outcome.getOrNull().toOption(), it.message.metadata),
                        it.reason
                    )
                )
        }

        throwIfSucceeded(clazz, condition)
    }

    internal suspend fun <T : Any> waitUntilPublished(
        atLeastIn: Duration,
        clazz: KClass<T>,
        condition: (message: ParsedMessage<T>) -> Boolean
    ) {
        val getRecords = { producedRecords.map { it.value } }
        getRecords.waitUntilConditionMet(atLeastIn, "While PUBLISHING ${clazz.java.simpleName}") {
            val outcome = readCatching(it.value().toString(), clazz)
            outcome.isSuccess && condition(ParsedMessage(outcome.getOrNull().toOption(), it.toMetadata()))
        }

        throwIfFailed(clazz, condition)
    }

    private fun extractCause(listenerException: Throwable): Throwable =
        when (listenerException) {
            is ListenerExecutionFailedException ->
                listenerException.cause
                    ?: AssertionError("No cause found: Listener was not able to capture the cause")

            else -> listenerException
        }

    private fun <T : Any> readCatching(
        json: String,
        clazz: KClass<T>
    ): Result<T> = runCatching { objectMapper.readValue(json, clazz.java) }

    private fun <T : Any> throwIfFailed(
        clazz: KClass<T>,
        selector: (message: ParsedMessage<T>) -> Boolean
    ) = exceptions
        .filter {
            selector(
                ParsedMessage(
                    readCatching(it.value.message.actual.toString(), clazz).getOrNull().toOption(),
                    it.value.message.metadata
                )
            )
        }
        .forEach { throw it.value.reason }

    private fun <T : Any> throwIfSucceeded(
        clazz: KClass<T>,
        selector: (FailedParsedMessage<T>) -> Boolean
    ): Unit = consumedRecords
        .filter { record ->
            selector(
                FailedParsedMessage(
                    ParsedMessage(readCatching(record.value.value(), clazz).getOrNull().toOption(), record.value.toMetadata()),
                    getExceptionFor(clazz, selector)
                )
            )
        }
        .forEach { throw AssertionError("Expected to fail but succeeded: $it") }

    private fun <T : Any> getExceptionFor(
        clazz: KClass<T>,
        selector: (message: FailedParsedMessage<T>) -> Boolean
    ): Throwable = exceptions
        .map { it.value }
        .first {
            selector(
                FailedParsedMessage(
                    ParsedMessage(
                        readCatching(it.message.actual.toString(), clazz).getOrNull().toOption(),
                        it.message.metadata
                    ),
                    it.reason
                )
            )
        }
        .reason

    private suspend fun <T : Any> (() -> Collection<T>).waitUntilConditionMet(
        duration: Duration,
        subject: String,
        condition: (T) -> Boolean
    ): Collection<T> = runCatching {
        val collectionFunc = this
        withTimeout(duration) { while (!collectionFunc().any { condition(it) }) delay(50) }
        return collectionFunc().filter { condition(it) }
    }.recoverCatching {
        when (it) {
            is TimeoutCancellationException -> throw AssertionError("GOT A TIMEOUT: $subject. ${dumpMessages()}")
            is ConcurrentModificationException ->
                Result.success(waitUntilConditionMet(duration, subject, condition))

            else -> throw it
        }.getOrThrow()
    }.getOrThrow()

    private fun dumpMessages(): String =
        """
        Messages in the KafkaSystem so far:
        PUBLISHED MESSAGES: 
        ${producedRecords.map { it.value.value() }.joinToString("\n")}
        ------------------------
        CONSUMED MESSAGES: 
        ${consumedRecords.map { it.value.value() }.joinToString("\n")}
        """.trimIndent()
}

internal fun <K, V : Any> ProducerRecord<K, V>.toMetadata(): MessageMetadata = MessageMetadata(
    this.topic(),
    this.key().toString(),
    this.headers().associate { h -> Pair(h.key(), String(h.value())) }
)

internal fun <K, V : Any> ConsumerRecord<K, V>.toMetadata(): MessageMetadata = MessageMetadata(
    this.topic(),
    this.key().toString(),
    this.headers().associate { h -> Pair(h.key(), String(h.value())) }
)
