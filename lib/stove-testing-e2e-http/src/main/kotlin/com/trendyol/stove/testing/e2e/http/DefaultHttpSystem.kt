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
import com.trendyol.stove.testing.e2e.system.abstractions.ExperimentalStoveDsl
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

@Deprecated(
    "This method is deprecated, going to be removed",
    replaceWith = ReplaceWith("withHttpClient()", "com.trendyol.stove.testing.e2e.http.TestSystem")
)
fun TestSystem.withDefaultHttp(objectMapper: ObjectMapper = StoveObjectMapper.Default): TestSystem {
    this.getOrRegister(DefaultHttpSystem(this, objectMapper))
    return this
}

fun TestSystem.withHttpClient(options: HttpClientSystemOptions = HttpClientSystemOptions()): TestSystem {
    this.getOrRegister(DefaultHttpSystem(this, options.objectMapper))
    return this
}

@ExperimentalStoveDsl
fun WithDsl.httpClient(configure: () -> HttpClientSystemOptions = { HttpClientSystemOptions() }): TestSystem =
    this.testSystem.withHttpClient(configure())

@Deprecated(
    "This method is deprecated, going to be removed",
    replaceWith = ReplaceWith("http()", "com.trendyol.stove.testing.e2e.http.TestSystem")
)
fun TestSystem.defaultHttp(): DefaultHttpSystem =
    getOrNone<DefaultHttpSystem>().getOrElse {
        throw SystemNotRegisteredException(DefaultHttpSystem::class)
    }

fun TestSystem.http(): DefaultHttpSystem =
    getOrNone<DefaultHttpSystem>().getOrElse {
        throw SystemNotRegisteredException(DefaultHttpSystem::class)
    }

suspend fun ValidationDsl.http(validation: suspend DefaultHttpSystem.() -> Unit): Unit =
    validation(this.testSystem.http())

class DefaultHttpSystem(
    override val testSystem: TestSystem,
    private val objectMapper: ObjectMapper
) : PluggedSystem {
    private val httpClient: HttpClient = httpClient()

    @PublishedApi
    internal suspend fun <TExpected : Any> postAndExpectJson(
        uri: String,
        body: Option<Any>,
        clazz: KClass<TExpected>,
        token: Option<String>,
        expect: suspend (actual: TExpected) -> Unit
    ): DefaultHttpSystem = httpClient.send(uri) { request ->
        token.map { request.setHeader(Headers.Authorization, Headers.bearer(it)) }
        body.fold(
            ifEmpty = { request.POST(BodyPublishers.noBody()) },
            ifSome = { request.POST(BodyPublishers.ofString(objectMapper.writeValueAsString(it))) }
        )
    }.let { expect(deserialize(it, clazz)); this }

    @PublishedApi
    internal suspend fun postAndExpectBodilessResponse(
        uri: String,
        token: Option<String>,
        body: Option<Any>,
        expect: suspend (StoveHttpResponse) -> Unit
    ): DefaultHttpSystem = httpClient.send(uri) { request ->
        token.map { request.setHeader(Headers.Authorization, Headers.bearer(it)) }
        body.fold(
            ifEmpty = { request.POST(BodyPublishers.noBody()) },
            ifSome = { request.POST(BodyPublishers.ofString(objectMapper.writeValueAsString(it))) }
        )
    }.let { expect(StoveHttpResponse(it.statusCode(), it.headers().map())); this }

    @PublishedApi
    internal suspend fun <TExpected : Any> get(
        uri: String,
        queryParams: Map<String, String>,
        clazz: KClass<TExpected>,
        token: Option<String>,
        expect: suspend (TExpected) -> Unit
    ): DefaultHttpSystem = httpClient.send(uri, queryParams) { request ->
        token.map { request.setHeader(Headers.Authorization, Headers.bearer(it)) }
        request.GET()
    }.let { expect(deserialize(it, clazz)); this }

    @PublishedApi
    internal suspend fun <TExpected : Any> getMany(
        uri: String,
        queryParams: Map<String, String>,
        clazz: KClass<TExpected>,
        token: Option<String>,
        expect: suspend (List<TExpected>) -> Unit
    ): DefaultHttpSystem = httpClient.send(uri, queryParams) { request ->
        token.map { request.setHeader(Headers.Authorization, Headers.bearer(it)) }
        request.GET()
    }.let {
        expect(
            objectMapper.readValue(
                it.body(),
                objectMapper.typeFactory.constructCollectionType(List::class.java, clazz.javaObjectType)
            )
        ); this
    }

    @PublishedApi
    internal suspend fun getResponse(
        uri: String,
        queryParams: Map<String, String>,
        token: Option<String>,
        expect: suspend (StoveHttpResponse) -> Unit
    ): DefaultHttpSystem = httpClient.send(uri, queryParams) { request ->
        token.map { request.setHeader(Headers.Authorization, Headers.bearer(it)) }
        request
    }.let { expect(StoveHttpResponse(it.statusCode(), it.headers().map())); this }

    @PublishedApi
    internal suspend fun deleteAndExpectBodilessResponse(
        uri: String,
        token: Option<String>,
        expect: suspend (StoveHttpResponse) -> Unit
    ): DefaultHttpSystem = httpClient.send(uri) { request ->
        token.map { request.setHeader(Headers.Authorization, Headers.bearer(it)) }
        request.DELETE()
    }.let { expect(StoveHttpResponse(it.statusCode(), it.headers().map())); this }

    @PublishedApi
    internal suspend fun putAndExpectBodilessResponse(
        uri: String,
        token: Option<String>,
        body: Option<Any>,
        expect: suspend (StoveHttpResponse) -> Unit
    ): DefaultHttpSystem = httpClient.send(uri) { request ->
        token.map { request.setHeader(Headers.Authorization, Headers.bearer(it)) }
        body.fold(
            ifEmpty = { request.PUT(BodyPublishers.noBody()) },
            ifSome = { request.PUT(BodyPublishers.ofString(objectMapper.writeValueAsString(it))) }
        )
    }.let { expect(StoveHttpResponse(it.statusCode(), it.headers().map())); this }

    @PublishedApi
    internal suspend fun <TExpected : Any> putAndExpectJson(
        uri: String,
        body: Option<Any>,
        clazz: KClass<TExpected>,
        token: Option<String>,
        expect: suspend (actual: TExpected) -> Unit
    ): DefaultHttpSystem = httpClient.send(uri) { request ->
        token.map { request.setHeader(Headers.Authorization, Headers.bearer(it)) }
        body.fold(
            ifEmpty = { request.PUT(BodyPublishers.noBody()) },
            ifSome = { request.PUT(BodyPublishers.ofString(objectMapper.writeValueAsString(it))) }
        )
    }.let { expect(deserialize(it, clazz)); this }

    override fun then(): TestSystem = testSystem

    private suspend fun HttpClient.send(
        uri: String,
        queryParams: Map<String, String> = mapOf(),
        configureRequest: (request: HttpRequest.Builder) -> HttpRequest.Builder
    ): HttpResponse<ByteArray> {
        val requestBuilder = HttpRequest.newBuilder()
            .uri(relative(uri, queryParams))
            .setHeader(Headers.ContentType, MediaType.ApplicationJson)
        return sendAsync(configureRequest(requestBuilder).build(), BodyHandlers.ofByteArray()).await()
    }

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

    private fun <TExpected : Any> deserialize(
        it: HttpResponse<ByteArray>,
        clazz: KClass<TExpected>
    ): TExpected = when {
        clazz.java.isAssignableFrom(String::class.java) -> String(it.body()) as TExpected
        else -> objectMapper.readValue(it.body(), clazz.java)
    }

    override fun close() {}

    companion object {
        object MediaType {
            const val ApplicationJson = "application/json"
        }

        object Headers {
            const val ContentType = "Content-Type"
            const val Authorization = "Authorization"

            fun bearer(token: String) = "Bearer $token"
        }

        fun DefaultHttpSystem.client(): HttpClient = this.httpClient

        /**
         * Extension for: [DefaultHttpSystem.get]
         * */
        suspend inline fun <reified TExpected : Any> DefaultHttpSystem.get(
            uri: String,
            queryParams: Map<String, String> = mapOf(),
            token: Option<String> = None,
            noinline expect: suspend (TExpected) -> Unit
        ): DefaultHttpSystem = this.get(uri, queryParams, TExpected::class, token, expect)

        /**
         * Extension for: [DefaultHttpSystem.get]
         * */
        suspend inline fun <reified TExpected : Any> DefaultHttpSystem.get(
            uri: String,
            queryParams: Map<String, String> = mapOf(),
            noinline expect: suspend (TExpected) -> Unit
        ): DefaultHttpSystem = this.get(uri, queryParams, TExpected::class, None, expect)

        /**
         * Extension for: [DefaultHttpSystem.getResponse]
         * */
        suspend fun DefaultHttpSystem.getResponse(
            uri: String,
            queryParams: Map<String, String> = mapOf(),
            expect: suspend (StoveHttpResponse) -> Unit
        ): DefaultHttpSystem = this.getResponse(uri, queryParams, None, expect)

        /**
         * Extension for: [DefaultHttpSystem.getMany]
         * */
        suspend inline fun <reified TExpected : Any> DefaultHttpSystem.getMany(
            uri: String,
            queryParams: Map<String, String> = mapOf(),
            token: Option<String> = None,
            noinline expect: suspend (List<TExpected>) -> Unit
        ): DefaultHttpSystem = this.getMany(uri, queryParams, TExpected::class, token, expect)

        /**
         * Extension for: [DefaultHttpSystem.getMany]
         * */
        suspend inline fun <reified TExpected : Any> DefaultHttpSystem.getMany(
            uri: String,
            queryParams: Map<String, String> = mapOf(),
            noinline expect: suspend (List<TExpected>) -> Unit
        ): DefaultHttpSystem = this.getMany(uri, queryParams, TExpected::class, None, expect)

        /**
         * Extension for: [DefaultHttpSystem.postAndExpectJson]
         * */
        suspend inline fun <reified TExpected : Any> DefaultHttpSystem.postAndExpectJson(
            uri: String,
            body: Option<Any> = None,
            token: Option<String> = None,
            noinline expect: suspend (actual: TExpected) -> Unit
        ): DefaultHttpSystem = this.postAndExpectJson(uri, body, TExpected::class, token, expect)

        /**
         * Extension for: [DefaultHttpSystem.postAndExpectJson]
         * */
        suspend inline fun <reified TExpected : Any> DefaultHttpSystem.postAndExpectJson(
            uri: String,
            body: Option<Any> = None,
            noinline expect: suspend (actual: TExpected) -> Unit
        ): DefaultHttpSystem = this.postAndExpectJson(uri, body, TExpected::class, None, expect)

        /**
         * Extension for: [DefaultHttpSystem.postAndExpectJson]
         * */
        suspend inline fun <reified TExpected : Any> DefaultHttpSystem.postAndExpectJson(
            uri: String,
            noinline expect: suspend (actual: TExpected) -> Unit
        ): DefaultHttpSystem = this.postAndExpectJson(uri, None, TExpected::class, None, expect)

        /**
         * Extension for: [DefaultHttpSystem.postAndExpectBodilessResponse]
         * */
        suspend inline fun DefaultHttpSystem.postAndExpectBodilessResponse(
            uri: String,
            body: Option<Any> = None,
            noinline expect: suspend (actual: StoveHttpResponse) -> Unit
        ): DefaultHttpSystem = this.postAndExpectBodilessResponse(uri, None, body, expect)

        /**
         * Extension for: [DefaultHttpSystem.postAndExpectBodilessResponse]
         * */
        suspend inline fun DefaultHttpSystem.postAndExpectBodilessResponse(
            uri: String,
            noinline expect: suspend (actual: StoveHttpResponse) -> Unit
        ): DefaultHttpSystem = this.postAndExpectBodilessResponse(uri, None, None, expect)

        /**
         * Extension for: [DefaultHttpSystem.deleteAndExpectBodilessResponse]
         * */
        suspend inline fun DefaultHttpSystem.deleteAndExpectBodilessResponse(
            uri: String,
            noinline expect: suspend (actual: StoveHttpResponse) -> Unit
        ): DefaultHttpSystem = this.deleteAndExpectBodilessResponse(uri, None, expect)

        suspend inline fun <reified TExpected : Any> DefaultHttpSystem.putAndExpectJson(
            uri: String,
            body: Option<Any> = None,
            noinline expect: suspend (actual: TExpected) -> Unit
        ): DefaultHttpSystem = this.putAndExpectJson(uri, body, TExpected::class, None, expect)

        /**
         * Extension for: [DefaultHttpSystem.putAndExpectJson]
         * */
        suspend inline fun <reified TExpected : Any> DefaultHttpSystem.putAndExpectJson(
            uri: String,
            noinline expect: suspend (actual: TExpected) -> Unit
        ): DefaultHttpSystem = this.putAndExpectJson(uri, None, TExpected::class, None, expect)

        /**
         * Extension for: [DefaultHttpSystem.putAndExpectBodilessResponse]
         * */
        suspend inline fun DefaultHttpSystem.putAndExpectBodilessResponse(
            uri: String,
            body: Option<Any> = None,
            noinline expect: suspend (actual: StoveHttpResponse) -> Unit
        ): DefaultHttpSystem = this.putAndExpectBodilessResponse(uri, None, body, expect)

        /**
         * Extension for: [DefaultHttpSystem.putAndExpectBodilessResponse]
         * */
        suspend inline fun DefaultHttpSystem.putAndExpectBodilessResponse(
            uri: String,
            noinline expect: suspend (actual: StoveHttpResponse) -> Unit
        ): DefaultHttpSystem = this.putAndExpectBodilessResponse(uri, None, None, expect)
    }
}
