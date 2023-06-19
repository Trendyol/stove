package com.trendyol.stove.testing.e2e.wiremock

import arrow.core.getOrElse
import com.fasterxml.jackson.databind.ObjectMapper
import com.trendyol.stove.testing.e2e.serialization.StoveObjectMapper
import com.trendyol.stove.testing.e2e.system.TestSystem
import com.trendyol.stove.testing.e2e.system.ValidationDsl
import com.trendyol.stove.testing.e2e.system.WithDsl
import com.trendyol.stove.testing.e2e.system.abstractions.ExperimentalStoveDsl
import com.trendyol.stove.testing.e2e.system.abstractions.SystemNotRegisteredException
import com.trendyol.stove.testing.e2e.system.abstractions.SystemOptions

data class WireMockSystemOptions(
    /**
     * Port of wiremock server
     */
    val port: Int = 8080,

    /**
     * Removes the stub when request matches/completes
     * Default value is false
     */
    val removeStubAfterRequestMatched: Boolean = false,

    /**
     * Called after stub removed
     */
    val afterStubRemoved: AfterStubRemoved = { _, _, _ -> },

    /**
     * Called after request handled
     */
    val afterRequest: AfterRequestHandler = { _, _, _ -> },

    /**
     * ObjectMapper for serialization/deserialization
     */
    val objectMapper: ObjectMapper = StoveObjectMapper.Default
) : SystemOptions

data class WireMockContext(
    val port: Int,

    val removeStubAfterRequestMatched: Boolean,

    val afterStubRemoved: AfterStubRemoved,

    val afterRequest: AfterRequestHandler,

    val objectMapper: ObjectMapper
)

fun TestSystem.withWireMock(
    options: WireMockSystemOptions = WireMockSystemOptions()
): TestSystem = WireMockSystem(
    testSystem = this,
    WireMockContext(
        options.port,
        options.removeStubAfterRequestMatched,
        options.afterStubRemoved,
        options.afterRequest,
        options.objectMapper
    )
).also { getOrRegister(it) }
    .let { this }

@ExperimentalStoveDsl
fun WithDsl.wiremock(configure: () -> WireMockSystemOptions): TestSystem =
    this.testSystem.withWireMock(configure())

fun TestSystem.wiremock(): WireMockSystem = getOrNone<WireMockSystem>().getOrElse {
    throw SystemNotRegisteredException(WireMockSystem::class)
}

suspend fun ValidationDsl.wiremock(validation: suspend WireMockSystem.() -> Unit): Unit =
    validation(this.testSystem.wiremock())
