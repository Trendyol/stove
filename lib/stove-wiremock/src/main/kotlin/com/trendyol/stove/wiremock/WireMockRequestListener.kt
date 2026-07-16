package com.trendyol.stove.wiremock

import com.github.benmanes.caffeine.cache.Cache
import com.github.tomakehurst.wiremock.extension.*
import com.github.tomakehurst.wiremock.stubbing.*
import java.util.*

class WireMockRequestListener(
  private val stubLog: Cache<UUID, StubMapping>,
  private val afterRequest: AfterRequestHandler
) : ServeEventListener {
  private var recordRequest: (ServeEvent) -> Unit = {}
  private var onComplete: (ServeEvent) -> Unit = {}

  internal constructor(
    stubLog: Cache<UUID, StubMapping>,
    afterRequest: AfterRequestHandler,
    recordRequest: (ServeEvent) -> Unit,
    onComplete: (ServeEvent) -> Unit = {}
  ) : this(stubLog, afterRequest) {
    this.recordRequest = recordRequest
    this.onComplete = onComplete
  }

  override fun getName(): String = WireMockRequestListener::class.java.simpleName

  override fun beforeResponseSent(
    serveEvent: ServeEvent?,
    parameters: Parameters?
  ) {
    val event = serveEvent!!
    recordRequest(event)
    afterRequest(event, stubLog)
  }

  // The response has been fully transmitted here, so timing is complete —
  // this is where completed-exchange observers (dashboard interactions) hook in.
  override fun afterComplete(
    serveEvent: ServeEvent?,
    parameters: Parameters?
  ) {
    serveEvent?.let(onComplete)
  }
}
