package com.trendyol.stove.testing.e2e.messaging

import arrow.core.None
import arrow.core.Option
import com.trendyol.stove.testing.e2e.system.abstractions.PluggedSystem

/**
 * Messaging abstraction for the testing system. Every messaging system more or less has the same behaviour. [MessagingSystem]
 * defines an abstraction point for these tools. The popular implementation is Kafka, and Stove Testing supports it. As an example:
 *
 * ### To set it up:
 *
 * ```kotlin
 * TestSystem().withKafka()
 * ```
 *
 * ### Usage in testing:
 * ```kotlin
 * TestSystem.instance
 * .kafka()
 * .publish("test.topic", TestEvent(id = "id-test"))
 * .shouldBeConsumed(message = TestEvent(id = "id-test"))
 * .shouldBeConsumedOnCondition<TestEvent> { actual ->
 *     actual.id == "id-test"
 * }
 * ```
 * @author Oguzhan Soykan
 */
interface MessagingSystem : AssertsConsuming, PluggedSystem {

    /**
     * Publishes a message to configured messaging system
     * [message] message to be sent
     * [key] is the message key that has a meaning for underlying messaging system when messages are routed to the partitions
     * [headers] custom headers if the message needs, an automatic header for [testCase] is always added if provided
     * [testCase] is a string that indicates which test is the message published from, you can see it in the logs (only if level is info)
     */
    suspend fun publish(
        topic: String,
        message: Any,
        key: Option<String> = None,
        headers: Map<String, String> = mapOf(),
        testCase: Option<String> = None,
    ): MessagingSystem
}
