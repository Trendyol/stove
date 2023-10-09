package com.trendyol.stove.testing.e2e.wiremock

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.extension.Parameters
import com.github.tomakehurst.wiremock.extension.ServeEventListener
import com.github.tomakehurst.wiremock.stubbing.ServeEvent
import com.github.tomakehurst.wiremock.stubbing.StubMapping
import com.trendyol.stove.functional.Try
import com.trendyol.stove.functional.recover
import wiremock.org.slf4j.Logger
import wiremock.org.slf4j.LoggerFactory
import java.util.*
import java.util.concurrent.ConcurrentMap

class WireMockVacuumCleaner(
    private val stubLog: ConcurrentMap<UUID, StubMapping>,
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
                val stubToBeRemoved = stubLog[serveEvent.stubMapping.id]
                wireMock.removeStub(stubToBeRemoved)
                wireMock.removeServeEvent(serveEvent.id)
                synchronized(stubLog) {
                    stubLog.remove(serveEvent.stubMapping.id)
                }
                afterStubRemoved(serveEvent, stubLog)
            }
        }.recover { throwable -> logger.warn(throwable.message) }
    }
}
