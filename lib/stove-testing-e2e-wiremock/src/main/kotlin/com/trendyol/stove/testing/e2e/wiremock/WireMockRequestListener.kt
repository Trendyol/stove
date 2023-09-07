package com.trendyol.stove.testing.e2e.wiremock

import com.github.tomakehurst.wiremock.extension.Parameters
import com.github.tomakehurst.wiremock.extension.ServeEventListener
import com.github.tomakehurst.wiremock.stubbing.ServeEvent
import com.github.tomakehurst.wiremock.stubbing.StubMapping
import java.util.*
import java.util.concurrent.ConcurrentMap

class WireMockRequestListener(
    private val stubLog: ConcurrentMap<UUID, StubMapping>,
    private val afterRequest: AfterRequestHandler
) : ServeEventListener {
    override fun getName(): String = WireMockRequestListener::class.java.simpleName

    override fun beforeResponseSent(
        serveEvent: ServeEvent?,
        parameters: Parameters?
    ): Unit = afterRequest(serveEvent!!, stubLog)
}
