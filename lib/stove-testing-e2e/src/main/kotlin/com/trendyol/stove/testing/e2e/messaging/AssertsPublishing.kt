package com.trendyol.stove.testing.e2e.messaging

import kotlin.reflect.KClass
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

interface AssertsPublishing {

    /**
     * Expects a message to be published [atLeastIn] given time
     * Expected message instance should be given with [message]
     *
     *  Kafka Example:
     * ```kotlin
     * TestSystem.instance
     * .kafka()
     * .shouldBePublished(message = TestEvent(id= "test-id"))
     * ```
     */
    suspend fun shouldBePublished(
        atLeastIn: Duration = 5.seconds,
        message: Any,
    ): MessagingSystem

    /**
     * Expects a predicate over a message type of [T].
     * Use the extension of [Companion.shouldBePublishedOnCondition] to be able to  pass [T] in a generic way.
     * The method waits until the condition is satisfied, otherwise throws [AssertionError] indicating that `Publishing Failed`
     *
     * Example:
     * ```kotlin
     * TestSystem.instance
     *  .kafka().shouldBePublishedOnCondition<TestEvent>{ actual ->
     *     actual.id == "id-to-match"
     *  }
     * ```
     */
    suspend fun <T : Any> shouldBePublishedOnCondition(
        atLeastIn: Duration = 5.seconds,
        condition: (T) -> Boolean,
        clazz: KClass<T>,
    ): MessagingSystem

    companion object {
        /**
         * Extension for [AssertsPublishing.shouldBePublishedOnCondition] to enable generic invocation as method<[T]>(...)
         */
        suspend inline fun <reified T : Any> AssertsPublishing.shouldBePublishedOnCondition(
            atLeastIn: Duration = 5.seconds,
            noinline condition: (T) -> Boolean,
        ): MessagingSystem = this.shouldBePublishedOnCondition(atLeastIn, condition, T::class)
    }
}
