package com.trendyol.stove.testing.e2e.http

data class StoveHttpResponse(
    val status: Int,
    val headers: Map<String, Any>
)
