package com.trendyol.stove.testing.e2e.http

/**
 * Represents the bodiless Http response from a call that is invoked from [HttpSystem]
 *
 * See:
 * - [HttpSystem.postAndExpectBodilessResponse]
 * - [HttpSystem.getResponse]
 */
data class StoveHttpResponse(
    val status: Int,
    val headers: Map<String, Any>,
)
