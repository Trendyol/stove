package com.trendyol.stove.testing.e2e.http

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.trendyol.stove.testing.e2e.serialization.StoveObjectMapper

sealed class StoveHttpResponse(
    open val status: Int,
    open val headers: Map<String, Any>
) {
    data class Bodiless(
        override val status: Int,
        override val headers: Map<String, Any>
    ) : StoveHttpResponse(status, headers)

    data class WithBody(
        override val status: Int,
        override val headers: Map<String, Any>,
        val body: () -> ByteArray
    ) : StoveHttpResponse(status, headers) {
        inline fun <reified T> bodyAs(objectMapper: ObjectMapper = StoveObjectMapper.Default): T = objectMapper.readValue(body())
    }
}
