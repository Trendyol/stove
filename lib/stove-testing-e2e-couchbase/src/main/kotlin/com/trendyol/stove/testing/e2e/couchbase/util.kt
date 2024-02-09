package com.trendyol.stove.testing.e2e.couchbase

import com.couchbase.client.core.error.*
import com.couchbase.client.kotlin.Cluster
import com.couchbase.client.kotlin.query.execute
import com.trendyol.stove.functional.*
import kotlinx.coroutines.delay
import java.util.concurrent.TimeoutException
import kotlin.time.Duration.Companion.minutes

suspend fun Cluster.waitForKeySpaceAvailability(
    bucketName: String,
    keyspaceName: String,
    duration: kotlin.time.Duration,
    delayMillis: Long = 1000,
    logger: (log: String) -> Unit = ::println
): Unit = waitUntilSucceeds(
    continueIf = { it is CollectionNotFoundException },
    duration = duration,
    delayMillis = delayMillis,
    logger = logger
) { bucket(bucketName).defaultScope().collection(keyspaceName).exists("not-important") }

suspend fun Cluster.waitUntilIndexIsCreated(
    query: String,
    duration: kotlin.time.Duration,
    delayMillis: Long = 50,
    logger: (log: String) -> Unit = ::println
): Unit = waitUntilSucceeds(
    continueIf = { it is IndexFailureException },
    duration = duration,
    delayMillis = delayMillis,
    logger = logger
) { query(query, readonly = false).execute() }

suspend fun Cluster.waitUntilSucceeds(
    continueIf: (Throwable) -> Boolean,
    duration: kotlin.time.Duration = 10.minutes,
    delayMillis: Long = 50,
    logger: (log: String) -> Unit = ::println,
    block: suspend Cluster.() -> Unit
) {
    val startTime = System.currentTimeMillis()
    while (System.currentTimeMillis() - startTime < duration.inWholeMilliseconds) {
        val executed = Try {
            this.block()
            true
        }.recover { throwable ->
            logger("Operation failed.\nBecause of: $throwable")
            when {
                continueIf(throwable) -> false
                else -> throw throwable
            }
        }.get()

        if (executed) {
            logger("Operation executed successfully")
            return
        }

        logger("Operation is not successful. Waiting for $delayMillis ms...")
        delay(delayMillis)
    }

    throw TimeoutException("Timed out waiting for the operation!")
}
