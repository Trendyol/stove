package com.trendyol.stove.wiremock

import com.github.benmanes.caffeine.cache.Cache
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.extension.*
import com.github.tomakehurst.wiremock.stubbing.*
import com.trendyol.stove.functional.*
import wiremock.org.slf4j.*
import java.util.*

class WireMockVacuumCleaner(
  private val stubLog: Cache<UUID, StubMapping>,
  private val afterStubRemoved: AfterStubRemoved
) : ServeEventListener {
  private lateinit var wireMock: WireMockServer
  private val logger: Logger = LoggerFactory.getLogger(javaClass)

  override fun getName(): String = "StoveVacuumCleaner"

  fun wireMock(wireMockServer: WireMockServer) {
    this.wireMock = wireMockServer
  }

  override fun beforeResponseSent(
    serveEvent: ServeEvent,
    parameters: Parameters?
  ) {
    if (!serveEvent.wasMatched) {
      return
    }

    if (!stubLog.containsKey(serveEvent.stubMapping.id)) {
      return
    }

    Try {
      synchronized(wireMock) {
        val stubToBeRemoved = stubLog.getIfPresent(serveEvent.stubMapping.id)
        wireMock.removeStub(stubToBeRemoved)
        wireMock.removeServeEvent(serveEvent.id)
        stubLog.invalidate(serveEvent.stubMapping.id)
        afterStubRemoved(serveEvent, stubLog)
      }
    }.recover { throwable -> logger.warn(throwable.message) }
  }
}
