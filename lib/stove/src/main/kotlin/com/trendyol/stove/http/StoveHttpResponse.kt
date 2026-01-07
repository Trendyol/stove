package com.trendyol.stove.http

sealed class StoveHttpResponse(
  open val status: Int,
  open val headers: Map<String, Any>
) {
  data class Bodiless(
    override val status: Int,
    override val headers: Map<String, Any>
  ) : StoveHttpResponse(status, headers)

  data class WithBody<T>(
    override val status: Int,
    override val headers: Map<String, Any>,
    val body: suspend () -> T
  ) : StoveHttpResponse(status, headers)
}
