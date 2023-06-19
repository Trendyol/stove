package com.trendyol.stove.testing.e2e.wiremock

import com.github.tomakehurst.wiremock.core.Admin
import com.github.tomakehurst.wiremock.extension.PostServeAction
import com.github.tomakehurst.wiremock.stubbing.ServeEvent
import com.github.tomakehurst.wiremock.stubbing.StubMapping
import java.util.UUID
import java.util.concurrent.ConcurrentMap

class WireMockRequestListener(
    private val stubLog: ConcurrentMap<UUID, StubMapping>,
    private val afterRequest: AfterRequestHandler
) : PostServeAction() {
    override fun getName(): String = WireMockRequestListener::class.java.simpleName

    override fun doGlobalAction(
        serveEvent: ServeEvent,
        admin: Admin
    ): Unit = afterRequest(serveEvent, admin, stubLog)
}
