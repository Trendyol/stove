package com.trendyol.stove.testing.e2e.kafka.intercepting

import com.trendyol.stove.testing.e2e.serialization.StoveJsonSerializer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap
import org.apache.kafka.clients.admin.Admin
import org.apache.kafka.clients.consumer.Consumer
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class TestSystemKafkaInterceptor(
    override val adminClient: Admin,
    override val serde: StoveJsonSerializer,
    private val options: InterceptionOptions,
) : ConsumingOps, CommonOps {

    override val logger: Logger = LoggerFactory.getLogger(javaClass)
    override val consumedRecords: ConcurrentMap<UUID, ConsumerRecord<String, String>> = ConcurrentHashMap()
    override val exceptions: ConcurrentMap<UUID, Failure> = ConcurrentHashMap()
    override val assertions: ConcurrentMap<UUID, KafkaAssertion<*>> = ConcurrentHashMap()

    // TODO: Start a hook for failed kafka events

    fun onMessage(
        record: ConsumerRecord<String, String>,
        consumer: Consumer<String, String>,
    ): Unit = when {
        options.isErrorTopic(record.topic()) -> recordError(record)
        else -> recordMessage(record, consumer)
    }
}
