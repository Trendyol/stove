package com.trendyol.stove.testing.e2e.standalone.kafka.intercepting

import arrow.core.Option
import arrow.core.align
import arrow.core.handleErrorWith
import arrow.core.toOption
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.trendyol.stove.testing.e2e.standalone.kafka.KafkaSystem
import java.util.UUID
import java.util.concurrent.ConcurrentMap
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.apache.kafka.clients.admin.Admin
import org.apache.kafka.clients.consumer.Consumer
import org.apache.kafka.common.TopicPartition
import kotlin.math.abs
import kotlin.reflect.KClass
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

data class KafkaAssertion<T : Any>(
    val clazz: KClass<T>,
    val condition: (Option<T>) -> Boolean,
)

internal interface CommonOps : RecordsAssertions {
    val exceptions: ConcurrentMap<UUID, Failure>
    val serde: ObjectMapper
    val adminClient: Admin

    suspend fun <T> (() -> Collection<T>).waitUntilConditionMet(
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

    fun <T : Any> throwIfFailed(
        clazz: KClass<T>,
        selector: (Option<T>) -> Boolean,
    ): Unit = exceptions
        .filter { selector(readCatching(it.value.message.toString(), clazz).getOrNull().toOption()) }
        .forEach { throw it.value.reason }

    fun <T : Any> readCatching(
        json: Any,
        clazz: KClass<T>,
    ): Result<T> = runCatching {
        when (json) {
            is String -> serde.readValue(json, clazz.java)
            else -> jacksonObjectMapper().convertValue(json, clazz.java)
        }
    }

    fun reset(): Unit = exceptions.clear()

    fun dumpMessages(): String

    private fun consumerOffset(): Map<TopicPartition, Long> = adminClient.listConsumerGroups().all().get()
        .filterNot { it.groupId() == KafkaSystem.SubscribeToAllGroupId }
        .flatMap { group ->
            val offsets = adminClient.listConsumerGroupOffsets(group.groupId()).partitionsToOffsetAndMetadata().get()
            offsets.map { it.key to it.value.offset() }
        }.toMap()

    private fun producerOffset(
        consumer: Consumer<String, String>,
        consumerOffset: Map<TopicPartition, Long>,
    ): Map<TopicPartition, Long> {
        val partitions = consumerOffset.map {
            TopicPartition(it.key.topic(), it.key.partition())
        }
        return consumer.endOffsets(partitions)
    }

    private fun computeLag(
        consumerOffset: Map<TopicPartition, Long>,
        producerOffset: Map<TopicPartition, Long>,
    ): List<LagByTopic> = consumerOffset
        .align(producerOffset) {
            val lag = it.value.fold(fa = { 0 }, fb = { 0 }) { a, b ->
                abs(a - b)
            }
            LagByTopic(it.key, lag)
        }.map { it.value }

    private suspend fun waitUntilLagsComputed(
        consumer: Consumer<String, String>,
        forTopic: String,
    ): Collection<LagByTopic> = { computeLag(consumerOffset(), producerOffset(consumer, consumerOffset())) }
        .waitUntilConditionMet(5.seconds, "computing lag") {
            it.topicPartition.topic() == forTopic
        }

    data class LagByTopic(
        val topicPartition: TopicPartition,
        val lag: Long,
    )
}
