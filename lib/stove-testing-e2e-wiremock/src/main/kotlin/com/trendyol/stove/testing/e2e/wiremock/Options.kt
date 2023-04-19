package com.trendyol.stove.testing.e2e.wiremock

import arrow.core.getOrElse
import com.fasterxml.jackson.databind.ObjectMapper
import com.trendyol.stove.testing.e2e.serialization.StoveObjectMapper
import com.trendyol.stove.testing.e2e.system.TestSystem
import com.trendyol.stove.testing.e2e.system.ValidationDsl
import com.trendyol.stove.testing.e2e.system.abstractions.SystemNotRegisteredException
import com.trendyol.stove.testing.e2e.system.abstractions.SystemOptions

data class WireMockSystemOptions(
    /**
     * Removes the stub when request matches/completes
     */
    val removeStubAfterRequestMatched: Boolean = false,
    val afterStubRemoved: AfterStubRemoved = { _, _, _ -> },
    val afterRequest: AfterRequestHandler = { _, _, _ -> },
    val objectMapper: ObjectMapper = StoveObjectMapper.Default,
) : SystemOptions

fun TestSystem.withWireMock(
    port: Int = 8080,
    options: WireMockSystemOptions = WireMockSystemOptions(),
): TestSystem {
    val system = WireMockSystem(
        this,
        WireMockContext(
            port,
            options.removeStubAfterRequestMatched,
            options.afterStubRemoved,
            options.afterRequest,
            options.objectMapper
        )
    )
    this.getOrRegister(system)
    return this
}

data class WireMockContext(
    val port: Int,

    val removeStubAfterRequestMatched: Boolean,

    val afterStubRemoved: AfterStubRemoved,

    val afterRequest: AfterRequestHandler,

    val objectMapper: ObjectMapper,
)

fun TestSystem.wiremock(): WireMockSystem =
    getOrNone<WireMockSystem>().getOrElse {
        throw SystemNotRegisteredException(WireMockSystem::class)
    }

suspend fun ValidationDsl.wiremock(validation: suspend WireMockSystem.() -> Unit): Unit =
    validation(this.testSystem.wiremock())
