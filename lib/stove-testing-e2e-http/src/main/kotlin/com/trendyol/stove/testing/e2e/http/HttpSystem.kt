@file:Suppress("UNCHECKED_CAST", "MemberVisibilityCanBePrivate")

package com.trendyol.stove.testing.e2e.http

import arrow.core.None
import arrow.core.Option
import arrow.core.getOrElse
import com.fasterxml.jackson.databind.ObjectMapper
import com.trendyol.stove.testing.e2e.serialization.StoveObjectMapper
import com.trendyol.stove.testing.e2e.system.TestSystem
import com.trendyol.stove.testing.e2e.system.ValidationDsl
import com.trendyol.stove.testing.e2e.system.WithDsl
import com.trendyol.stove.testing.e2e.system.abstractions.PluggedSystem
import com.trendyol.stove.testing.e2e.system.abstractions.SystemNotRegisteredException
import com.trendyol.stove.testing.e2e.system.abstractions.SystemOptions
import kotlinx.coroutines.future.await
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpClient.Redirect.ALWAYS
import java.net.http.HttpClient.Version.HTTP_2
import java.net.http.HttpRequest
import java.net.http.HttpRequest.BodyPublishers
import java.net.http.HttpResponse
import java.net.http.HttpResponse.BodyHandlers
import java.time.Duration
import kotlin.reflect.KClass

data class HttpClientSystemOptions(val objectMapper: ObjectMapper = StoveObjectMapper.Default) : SystemOptions

internal fun TestSystem.withHttpClient(options: HttpClientSystemOptions = HttpClientSystemOptions()): TestSystem {
    this.getOrRegister(HttpSystem(this, options.objectMapper))
    return this
}

internal fun TestSystem.http(): HttpSystem =
    getOrNone<HttpSystem>().getOrElse {
        throw SystemNotRegisteredException(HttpSystem::class)
    }

fun WithDsl.httpClient(configure: () -> HttpClientSystemOptions = { HttpClientSystemOptions() }): TestSystem =
    this.testSystem.withHttpClient(configure())

suspend fun ValidationDsl.http(validation: suspend HttpSystem.() -> Unit): Unit =
    validation(this.testSystem.http())

class HttpSystem(
    override val testSystem: TestSystem,
    @PublishedApi internal val objectMapper: ObjectMapper
) : PluggedSystem {

    @PublishedApi
    internal val httpClient: HttpClient = httpClient()

    @Suppress("unused")
    suspend fun getResponse(
        uri: String,
        queryParams: Map<String, String> = mapOf(),
        headers: Map<String, String> = mapOf(),
        token: Option<String> = None,
        expect: suspend (StoveHttpResponse) -> Unit
    ): HttpSystem = httpClient.send(uri, headers = headers, queryParams = queryParams) { request ->
        token.map { request.setHeader(Headers.AUTHORIZATION, Headers.bearer(it)) }
        request
    }.let {
        expect(StoveHttpResponse(it.statusCode(), it.headers().map()))
        this
    }

    suspend fun postAndExpectBodilessResponse(
        uri: String,
        body: Option<Any>,
        token: Option<String> = None,
        headers: Map<String, String> = mapOf(),
        expect: suspend (StoveHttpResponse) -> Unit
    ): HttpSystem = httpClient.send(uri, headers = headers) { request ->
        token.map { request.setHeader(Headers.AUTHORIZATION, Headers.bearer(it)) }
        body.fold(
            ifEmpty = { request.POST(BodyPublishers.noBody()) },
            ifSome = { request.POST(BodyPublishers.ofString(objectMapper.writeValueAsString(it))) }
        )
    }.let {
        expect(StoveHttpResponse(it.statusCode(), it.headers().map()))
        this
    }

    suspend fun putAndExpectBodilessResponse(
        uri: String,
        body: Option<Any>,
        token: Option<String> = None,
        headers: Map<String, String> = mapOf(),
        expect: suspend (StoveHttpResponse) -> Unit
    ): HttpSystem = httpClient.send(uri, headers = headers) { request ->
        token.map { request.setHeader(Headers.AUTHORIZATION, Headers.bearer(it)) }
        body.fold(
            ifEmpty = { request.PUT(BodyPublishers.noBody()) },
            ifSome = { request.PUT(BodyPublishers.ofString(objectMapper.writeValueAsString(it))) }
        )
    }.let {
        expect(StoveHttpResponse(it.statusCode(), it.headers().map()))
        this
    }

    suspend fun deleteAndExpectBodilessResponse(
        uri: String,
        token: Option<String> = None,
        headers: Map<String, String> = mapOf(),
        expect: suspend (StoveHttpResponse) -> Unit
    ): HttpSystem = httpClient.send(uri, headers = headers) { request ->
        token.map { request.setHeader(Headers.AUTHORIZATION, Headers.bearer(it)) }
        request.DELETE()
    }.let {
        expect(StoveHttpResponse(it.statusCode(), it.headers().map()))
        this
    }

    suspend inline fun <reified TExpected : Any> get(
        uri: String,
        queryParams: Map<String, String> = mapOf(),
        headers: Map<String, String> = mapOf(),
        token: Option<String> = None,
        expect: (TExpected) -> Unit
    ): HttpSystem = httpClient.send(uri, headers = headers, queryParams = queryParams) { request ->
        token.map { request.setHeader(Headers.AUTHORIZATION, Headers.bearer(it)) }
        request.GET()
    }.let {
        expect(deserialize(it, TExpected::class))
        this
    }

    suspend inline fun <reified TExpected : Any> getMany(
        uri: String,
        queryParams: Map<String, String> = mapOf(),
        headers: Map<String, String> = mapOf(),
        token: Option<String> = None,
        expect: (List<TExpected>) -> Unit
    ): HttpSystem = httpClient.send(uri, headers = headers, queryParams = queryParams) { request ->
        token.map { request.setHeader(Headers.AUTHORIZATION, Headers.bearer(it)) }
        request.GET()
    }.let {
        expect(
            objectMapper.readValue(
                it.body(),
                objectMapper.typeFactory.constructCollectionType(List::class.java, TExpected::class.javaObjectType)
            )
        )
        this
    }

    suspend inline fun <reified TExpected : Any> postAndExpectJson(
        uri: String,
        body: Option<Any> = None,
        headers: Map<String, String> = mapOf(),
        token: Option<String> = None,
        expect: (actual: TExpected) -> Unit
    ): HttpSystem = httpClient.send(uri, headers = headers) { request ->
        token.map { request.setHeader(Headers.AUTHORIZATION, Headers.bearer(it)) }
        body.fold(
            ifEmpty = { request.POST(BodyPublishers.noBody()) },
            ifSome = { request.POST(BodyPublishers.ofString(objectMapper.writeValueAsString(it))) }
        )
    }.let {
        expect(deserialize(it, TExpected::class))
        this
    }

    suspend inline fun <reified TExpected : Any> putAndExpectJson(
        uri: String,
        body: Option<Any> = None,
        headers: Map<String, String> = mapOf(),
        token: Option<String> = None,
        expect: (actual: TExpected) -> Unit
    ): HttpSystem = httpClient.send(uri, headers = headers) { request ->
        token.map { request.setHeader(Headers.AUTHORIZATION, Headers.bearer(it)) }
        body.fold(
            ifEmpty = { request.PUT(BodyPublishers.noBody()) },
            ifSome = { request.PUT(BodyPublishers.ofString(objectMapper.writeValueAsString(it))) }
        )
    }.let {
        expect(deserialize(it, TExpected::class))
        this
    }

    override fun then(): TestSystem = testSystem

    @PublishedApi
    internal suspend fun HttpClient.send(
        uri: String,
        headers: Map<String, String> = mapOf(),
        queryParams: Map<String, String> = mapOf(),
        configureRequest: (request: HttpRequest.Builder) -> HttpRequest.Builder
    ): HttpResponse<ByteArray> {
        val requestBuilder = HttpRequest.newBuilder()
            .uri(relative(uri, queryParams))
            .addHeaders(headers)
        return sendAsync(configureRequest(requestBuilder).build(), BodyHandlers.ofByteArray()).await()
    }

    private fun HttpRequest.Builder.addHeaders(headers: Map<String, String>): HttpRequest.Builder = headers
        .toMutableMap()
        .apply { this[Headers.CONTENT_TYPE] = MediaType.APPLICATION_JSON }
        .forEach { (key, value) -> setHeader(key, value) }
        .let { this }

    private fun relative(
        uri: String,
        queryParams: Map<String, String> = mapOf()
    ): URI = URI.create(testSystem.baseUrl)
        .resolve(uri + queryParams.toParamsString())

    private fun Map<String, String>.toParamsString(): String = when {
        this.any() -> "?${this.map { "${it.key}=${URLEncoder.encode(it.value, Charsets.UTF_8)}" }.joinToString("&")}"
        else -> ""
    }

    private fun httpClient(): HttpClient {
        val builder = HttpClient.newBuilder()
        builder.connectTimeout(Duration.ofSeconds(5))
        builder.followRedirects(ALWAYS)
        builder.version(HTTP_2)
        return builder.build()
    }

    @PublishedApi
    internal fun <TExpected : Any> deserialize(
        it: HttpResponse<ByteArray>,
        clazz: KClass<TExpected>
    ): TExpected = when {
        clazz.java.isAssignableFrom(String::class.java) -> String(it.body()) as TExpected
        else -> objectMapper.readValue(it.body(), clazz.java)
    }

    override fun close() {}

    companion object {
        object MediaType {
            const val APPLICATION_JSON = "application/json"
        }

        object Headers {
            const val CONTENT_TYPE = "Content-Type"
            const val AUTHORIZATION = "Authorization"

            fun bearer(token: String) = "Bearer $token"
        }

        /**
         * Exposes the [HttpClient] used by the [HttpSystem].
         */
        @Suppress("unused")
        fun HttpSystem.client(): HttpClient = this.httpClient
    }
}
