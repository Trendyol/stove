package com.trendyol.stove.wiremock

import arrow.core.*
import com.github.tomakehurst.wiremock.client.*
import com.github.tomakehurst.wiremock.client.WireMock.*
import com.github.tomakehurst.wiremock.http.RequestMethod
import com.github.tomakehurst.wiremock.matching.*
import com.github.tomakehurst.wiremock.verification.LoggedRequest
import com.trendyol.stove.serialization.StoveSerde

internal class WireMockVerification(
  private val system: WireMockSystem,
  private val callJournal: WireMockCallJournal,
  private val serde: StoveSerde<Any, ByteArray>
) {
  suspend fun shouldHaveBeenCalled(
    method: RequestMethod,
    url: String,
    count: CountMatchingStrategy = exactly(1),
    requestBody: Option<Any> = None,
    requestContaining: Map<String, Any> = emptyMap(),
    headers: Map<String, String> = emptyMap(),
    queryParams: Map<String, String> = emptyMap(),
    urlPatternFn: (url: String) -> UrlPattern = { urlEqualTo(it) }
  ): WireMockSystem = shouldHaveBeenCalled(count) {
    requestPattern(
      method = method,
      url = url,
      requestBody = requestBody,
      requestContaining = requestContaining,
      headers = headers,
      queryParams = queryParams,
      urlPatternFn = urlPatternFn
    )
  }

  suspend fun shouldHaveBeenCalled(
    count: CountMatchingStrategy = exactly(1),
    request: @WiremockDsl () -> RequestPatternBuilder
  ): WireMockSystem =
    verifyCalls(
      action = WireMockReportActions.VERIFY_REQUEST_WAS_CALLED,
      count = count,
      requestPattern = request().build()
    )

  suspend fun shouldNotHaveBeenCalled(
    method: RequestMethod,
    url: String,
    requestBody: Option<Any> = None,
    requestContaining: Map<String, Any> = emptyMap(),
    headers: Map<String, String> = emptyMap(),
    queryParams: Map<String, String> = emptyMap(),
    urlPatternFn: (url: String) -> UrlPattern = { urlEqualTo(it) }
  ): WireMockSystem = shouldHaveBeenCalled(
    method = method,
    url = url,
    count = exactly(0),
    requestBody = requestBody,
    requestContaining = requestContaining,
    headers = headers,
    queryParams = queryParams,
    urlPatternFn = urlPatternFn
  )

  suspend fun shouldNotHaveBeenCalled(
    request: @WiremockDsl () -> RequestPatternBuilder
  ): WireMockSystem =
    verifyCalls(
      action = WireMockReportActions.VERIFY_REQUEST_WAS_NOT_CALLED,
      count = exactly(0),
      requestPattern = request().build()
    )

  fun callsFor(
    method: RequestMethod,
    url: String,
    requestBody: Option<Any> = None,
    requestContaining: Map<String, Any> = emptyMap(),
    headers: Map<String, String> = emptyMap(),
    queryParams: Map<String, String> = emptyMap(),
    urlPatternFn: (url: String) -> UrlPattern = { urlEqualTo(it) }
  ): List<LoggedRequest> = callsFor(
    requestPattern(
      method = method,
      url = url,
      requestBody = requestBody,
      requestContaining = requestContaining,
      headers = headers,
      queryParams = queryParams,
      urlPatternFn = urlPatternFn
    ).build()
  )

  fun callsFor(
    request: @WiremockDsl () -> RequestPatternBuilder
  ): List<LoggedRequest> = callsFor(request().build())

  private suspend fun verifyCalls(
    action: String,
    count: CountMatchingStrategy,
    requestPattern: RequestPattern
  ): WireMockSystem {
    val actualCount = callsFor(requestPattern).size

    system.report(
      action = action,
      input = requestPattern.toString().some(),
      expected = count.toString().some(),
      actual = WireMockValidationMessages.requestCount(actualCount).some()
    ) {
      if (!count.match(actualCount)) {
        throw VerificationException(requestPattern, count, actualCount)
      }
    }

    return system
  }

  private fun callsFor(requestPattern: RequestPattern): List<LoggedRequest> =
    callJournal.requests(system.reporter.currentTestId())
      .filter { request -> requestPattern.match(request).isExactMatch }

  private fun requestPattern(
    method: RequestMethod,
    url: String,
    requestBody: Option<Any>,
    requestContaining: Map<String, Any>,
    headers: Map<String, String>,
    queryParams: Map<String, String>,
    urlPatternFn: (url: String) -> UrlPattern
  ): RequestPatternBuilder {
    val request = RequestPatternBuilder.newRequestPattern(method, urlPatternFn(url))
    requestBody.map {
      request.withRequestBody(
        equalToJson(
          serde.serialize(it).decodeToString(),
          true,
          false
        )
      )
    }
    request.configureBodyContaining(requestContaining, serde)
    headers.forEach { (key, value) -> request.withHeader(key, equalTo(value)) }
    queryParams.forEach { (key, value) -> request.withQueryParam(key, equalTo(value)) }
    return request
  }
}
