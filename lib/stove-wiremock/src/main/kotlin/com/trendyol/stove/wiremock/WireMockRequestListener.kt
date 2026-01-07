package com.trendyol.stove.wiremock

import com.github.benmanes.caffeine.cache.Cache
import com.github.tomakehurst.wiremock.extension.*
import com.github.tomakehurst.wiremock.stubbing.*
import java.util.*

class WireMockRequestListener(
  private val stubLog: Cache<UUID, StubMapping>,
  private val afterRequest: AfterRequestHandler
) : ServeEventListener {
  override fun getName(): String = WireMockRequestListener::class.java.simpleName

  override fun beforeResponseSent(
    serveEvent: ServeEvent?,
    parameters: Parameters?
  ): Unit = afterRequest(serveEvent!!, stubLog)
}
