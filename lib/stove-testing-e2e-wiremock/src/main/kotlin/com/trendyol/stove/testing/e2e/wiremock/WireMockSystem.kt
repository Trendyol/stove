package com.trendyol.stove.testing.e2e.wiremock

import arrow.core.None
import arrow.core.Option
import arrow.core.getOrElse
import com.fasterxml.jackson.databind.ObjectMapper
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.MappingBuilder
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder
import com.github.tomakehurst.wiremock.client.WireMock
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.equalToJson
import com.github.tomakehurst.wiremock.core.Admin
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import com.github.tomakehurst.wiremock.extension.Extension
import com.github.tomakehurst.wiremock.matching.ContainsPattern
import com.github.tomakehurst.wiremock.stubbing.ServeEvent
import com.github.tomakehurst.wiremock.stubbing.StubMapping
import com.trendyol.stove.functional.Try
import com.trendyol.stove.functional.recover
import com.trendyol.stove.testing.e2e.httpmock.HttpMockSystem
import com.trendyol.stove.testing.e2e.serialization.StoveObjectMapper
import com.trendyol.stove.testing.e2e.system.TestSystem
import com.trendyol.stove.testing.e2e.system.abstractions.RunAware
import com.trendyol.stove.testing.e2e.system.abstractions.SystemNotRegisteredException
import com.trendyol.stove.testing.e2e.system.abstractions.SystemOptions
import kotlinx.coroutines.runBlocking
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap
import org.slf4j.Logger
import org.slf4j.LoggerFactory

typealias AfterStubRemoved = (ServeEvent, Admin, ConcurrentMap<UUID, StubMapping>) -> Unit
typealias AfterRequestHandler = (ServeEvent, Admin, ConcurrentMap<UUID, StubMapping>) -> Unit

data class WireMockSystemOptions(
    /**
     * Removes the stub when request matches/completes
     */
    val removeStubAfterRequestMatched: Boolean = false,
    val afterStubRemoved: AfterStubRemoved = { _, _, _ -> },
    val afterRequest: AfterRequestHandler = { _, _, _ -> },
    val objectMapper: ObjectMapper = StoveObjectMapper.Default,
) : SystemOptions

fun TestSystem.withWireMock(
    port: Int = 8080,
    options: WireMockSystemOptions = WireMockSystemOptions(),
): TestSystem {
    val system = WireMockSystem(
        this,
        WireMockContext(
            port,
            options.removeStubAfterRequestMatched,
            options.afterStubRemoved,
            options.afterRequest,
            options.objectMapper
        )
    )
    this.getOrRegister(system)
    return this
}

data class WireMockContext(
    val port: Int,

    val removeStubAfterRequestMatched: Boolean,

    val afterStubRemoved: AfterStubRemoved,

    val afterRequest: AfterRequestHandler,

    val objectMapper: ObjectMapper,
)

fun TestSystem.wiremock(): WireMockSystem =
    getOrNone<WireMockSystem>().getOrElse {
        throw SystemNotRegisteredException(WireMockSystem::class)
    }

class WireMockSystem(
    override val testSystem: TestSystem,
    ctx: WireMockContext,
) : HttpMockSystem<MappingBuilder>, RunAware {

    private val stubLog: ConcurrentMap<UUID, StubMapping> = ConcurrentHashMap()
    private var wireMock: WireMockServer
    private val json: ObjectMapper = ctx.objectMapper
    private val logger: Logger = LoggerFactory.getLogger(javaClass)

    init {
        val stoveExtensions = mutableListOf<Extension>()
        val cfg = wireMockConfig().port(ctx.port).extensions(WireMockRequestListener(stubLog, ctx.afterRequest))

        if (ctx.removeStubAfterRequestMatched) {
            stoveExtensions.add(WireMockVacuumCleaner(stubLog, ctx.afterStubRemoved))
        }
        stoveExtensions.map { cfg.extensions(it) }
        wireMock = WireMockServer(cfg)
        stoveExtensions.filterIsInstance<WireMockVacuumCleaner>().forEach { it.wireMock(wireMock) }
    }

    override suspend fun run(): Unit = wireMock.start()

    override suspend fun stop(): Unit = wireMock.stop()

    override fun mockGet(
        url: String,
        responseBody: Option<Any>,
        statusCode: Int,
        metadata: Map<String, Any>,
    ): WireMockSystem {
        val mockRequest = WireMock.get(WireMock.urlEqualTo(url))
        mockRequest.withMetadata(metadata)
        val mockResponse = configureBody(statusCode, responseBody)
        val stub = wireMock.stubFor(mockRequest.willReturn(mockResponse).withId(UUID.randomUUID()))
        stubLog.putIfAbsent(stub.id, stub)
        return this
    }

    override fun mockPost(
        url: String,
        requestBody: Option<Any>,
        responseBody: Option<Any>,
        statusCode: Int,
        metadata: Map<String, Any>,
    ): WireMockSystem {
        val mockRequest = WireMock.post(WireMock.urlEqualTo(url))
        configureBodyAndMetadata(mockRequest, metadata, requestBody)
        val mockResponse = configureBody(statusCode, responseBody)
        val stub = wireMock.stubFor(mockRequest.willReturn(mockResponse).withId(UUID.randomUUID()))
        stubLog.putIfAbsent(stub.id, stub)
        return this
    }

    override fun mockPut(
        url: String,
        requestBody: Option<Any>,
        responseBody: Option<Any>,
        statusCode: Int,
        metadata: Map<String, Any>,
    ): WireMockSystem {
        val res = aResponse()
            .withStatus(statusCode)
            .withHeader("Content-Type", "application/json; charset=UTF-8")
        responseBody.map { res.withBody(json.writeValueAsBytes(it)) }
        val req = WireMock.put(WireMock.urlEqualTo(url))
        configureBodyAndMetadata(req, metadata, requestBody)
        val stub = wireMock.stubFor(req.willReturn(res).withId(UUID.randomUUID()))
        stubLog.putIfAbsent(stub.id, stub)
        return this
    }

    override fun mockDelete(
        url: String,
        statusCode: Int,
        metadata: Map<String, Any>,
    ): WireMockSystem {
        val mockRequest = WireMock.delete(WireMock.urlEqualTo(url))
        configureBodyAndMetadata(mockRequest, metadata, None)

        val mockResponse = configureBody(statusCode, None)
        val stub = wireMock.stubFor(mockRequest.willReturn(mockResponse).withId(UUID.randomUUID()))
        stubLog.putIfAbsent(stub.id, stub)
        return this
    }

    override fun mockHead(
        url: String,
        statusCode: Int,
        metadata: Map<String, Any>,
    ): WireMockSystem {
        val mockRequest = WireMock.head(WireMock.urlEqualTo(url))
        configureBodyAndMetadata(mockRequest, metadata, None)

        val mockResponse = configureBody(statusCode, None)
        val stub = wireMock.stubFor(mockRequest.willReturn(mockResponse).withId(UUID.randomUUID()))
        stubLog.putIfAbsent(stub.id, stub)
        return this
    }

    override fun mockPutConfigure(
        url: String,
        configure: (MappingBuilder, ObjectMapper) -> MappingBuilder,
    ): WireMockSystem {
        val req = WireMock.put(WireMock.urlEqualTo(url))
        val stub = wireMock.stubFor(configure(req, json).withId(UUID.randomUUID()))
        stubLog.putIfAbsent(stub.id, stub)
        return this
    }

    override fun mockGetConfigure(
        url: String,
        configure: (MappingBuilder, ObjectMapper) -> MappingBuilder,
    ): WireMockSystem {
        val req = WireMock.get(WireMock.urlEqualTo(url))
        val stub = wireMock.stubFor(configure(req, json).withId(UUID.randomUUID()))
        stubLog.putIfAbsent(stub.id, stub)
        return this
    }

    override fun mockHeadConfigure(
        url: String,
        configure: (MappingBuilder, ObjectMapper) -> MappingBuilder,
    ): WireMockSystem {
        val req = WireMock.head(WireMock.urlEqualTo(url))
        val stub = wireMock.stubFor(configure(req, json).withId(UUID.randomUUID()))
        stubLog.putIfAbsent(stub.id, stub)
        return this
    }

    override fun mockDeleteConfigure(
        url: String,
        configure: (MappingBuilder, ObjectMapper) -> MappingBuilder,
    ): WireMockSystem {
        val req = WireMock.delete(WireMock.urlEqualTo(url))
        val stub = wireMock.stubFor(configure(req, json).withId(UUID.randomUUID()))
        stubLog.putIfAbsent(stub.id, stub)
        return this
    }

    override fun mockPostConfigure(
        url: String,
        configure: (MappingBuilder, ObjectMapper) -> MappingBuilder,
    ): WireMockSystem {
        val req = WireMock.post(WireMock.urlEqualTo(url))
        val stub = wireMock.stubFor(configure(req, json).withId(UUID.randomUUID()))
        stubLog.putIfAbsent(stub.id, stub)
        return this
    }

    override suspend fun validate() {
        data class ValidationResult(
            val url: String,
            val bodyAsString: String,
            val queryParams: String,
        ) {
            override fun toString(): String = """
                Url: $url
                Body: $bodyAsString
                QueryParams: $queryParams
            """.trimIndent()
        }
        if (wireMock.findAllUnmatchedRequests().any()) {
            val problems = wireMock.findAllUnmatchedRequests().joinToString("\n") {
                ValidationResult(
                    "${it.method.value()} ${it.url}",
                    it.bodyAsString,
                    json.writeValueAsString(it.queryParams)
                ).toString()
            }
            throw AssertionError(
                "There are unmatched requests in the mock pipeline, please satisfy all the wanted requests.\n$problems"
            )
        }
    }

    override fun close(): Unit = runBlocking {
        Try {
            stop()
        }.recover { logger.warn("got an error while stopping wiremock: ${it.message}") }
    }

    private fun configureBodyAndMetadata(
        request: MappingBuilder,
        metadata: Map<String, Any>,
        body: Option<Any>,
    ) {
        request.withMetadata(metadata)
        body.map {
            request.withRequestBody(
                equalToJson(
                    json.writeValueAsString(it),
                    true,
                    false
                )
            ).withHeader("Content-Type", ContainsPattern("application/json"))
        }
    }

    private fun configureBody(
        statusCode: Int,
        responseBody: Option<Any>,
    ): ResponseDefinitionBuilder? {
        val mockResponse = aResponse()
            .withStatus(statusCode)
            .withHeader("Content-Type", "application/json; charset=UTF-8")
        responseBody.map { mockResponse.withBody(json.writeValueAsBytes(it)) }
        return mockResponse
    }
}
