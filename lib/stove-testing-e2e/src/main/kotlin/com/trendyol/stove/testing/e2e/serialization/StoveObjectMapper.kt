package com.trendyol.stove.testing.e2e.serialization

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature.FAIL_ON_EMPTY_BEANS
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

object StoveObjectMapper {
    val Default: ObjectMapper = jacksonObjectMapper().disable(FAIL_ON_EMPTY_BEANS)
}
