package com.trendyol.stove.testing.e2e.wiremock

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.core.Admin
import com.github.tomakehurst.wiremock.extension.PostServeAction
import com.github.tomakehurst.wiremock.stubbing.ServeEvent
import com.github.tomakehurst.wiremock.stubbing.StubMapping
import com.trendyol.stove.functional.Try
import com.trendyol.stove.functional.recover
import java.util.UUID
import java.util.concurrent.ConcurrentMap
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class WireMockVacuumCleaner(
    private val stubLog: ConcurrentMap<UUID, StubMapping>,
    private val afterStubRemoved: AfterStubRemoved,
) : PostServeAction() {

    private lateinit var wireMock: WireMockServer
    private val logger: Logger = LoggerFactory.getLogger(javaClass)
    override fun getName(): String = "StoveVacuumCleaner"

    fun wireMock(wireMockServer: WireMockServer) {
        this.wireMock = wireMockServer
    }

    override fun doGlobalAction(
        serveEvent: ServeEvent,
        admin: Admin,
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
                afterStubRemoved(serveEvent, admin, stubLog)
            }
        }.recover { throwable -> logger.warn(throwable.message) }
    }
}
