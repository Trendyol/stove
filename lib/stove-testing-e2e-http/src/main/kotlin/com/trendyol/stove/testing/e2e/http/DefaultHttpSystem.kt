@file:Suppress("UNCHECKED_CAST")

package com.trendyol.stove.testing.e2e.http

import arrow.core.Option
import arrow.core.getOrElse
import com.trendyol.stove.testing.e2e.serialization.StoveJacksonJsonSerializer
import com.trendyol.stove.testing.e2e.serialization.StoveJsonSerializer
import com.trendyol.stove.testing.e2e.serialization.deserialize
import com.trendyol.stove.testing.e2e.system.TestSystem
import com.trendyol.stove.testing.e2e.system.abstractions.SystemNotRegisteredException
import com.trendyol.stove.testing.e2e.system.abstractions.SystemOptions
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpClient.Redirect.ALWAYS
import java.net.http.HttpClient.Version.HTTP_2
import java.net.http.HttpRequest
import java.net.http.HttpRequest.BodyPublishers
import java.net.http.HttpResponse
import java.net.http.HttpResponse.BodyHandlers
import java.time.Duration
import kotlinx.coroutines.future.await
import kotlin.reflect.KClass

data class HttpClientSystemOptions(val jsonSerializer: StoveJsonSerializer = StoveJacksonJsonSerializer()) : SystemOptions

@Deprecated(
    "This method is deprecated, going to be removed",
    replaceWith = ReplaceWith("withHttpClient()", "com.trendyol.stove.testing.e2e.http.TestSystem")
)
fun TestSystem.withDefaultHttp(jsonSerializer: StoveJsonSerializer = StoveJacksonJsonSerializer()): TestSystem {
    this.getOrRegister(DefaultHttpSystem(this, jsonSerializer))
    return this
}

fun TestSystem.withHttpClient(options: HttpClientSystemOptions = HttpClientSystemOptions()): TestSystem {
    this.getOrRegister(DefaultHttpSystem(this, options.jsonSerializer))
    return this
}

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

class DefaultHttpSystem(
    override val testSystem: TestSystem,
    private val json: StoveJsonSerializer,
) : HttpSystem {
    private val httpClient: HttpClient = httpClient()

    override suspend fun login(): DefaultHttpSystem {
        return this
    }

    override suspend fun <TExpected : Any> postAndExpectJson(
        uri: String,
        body: Option<Any>,
        clazz: KClass<TExpected>,
        token: Option<String>,
        expect: suspend (actual: TExpected) -> Unit,
    ): DefaultHttpSystem = httpClient.send(uri) { request ->
        token.map { request.setHeader(Headers.Authentication, Headers.bearer(it)) }
        body.fold(
            ifEmpty = { request.POST(BodyPublishers.noBody()) },
            ifSome = { request.POST(BodyPublishers.ofString(json.serialize(it))) }
        )
    }.let { expect(deserialize(it, clazz)); this }

    override suspend fun postAndExpectBodilessResponse(
        uri: String,
        token: Option<String>,
        body: Option<Any>,
        expect: suspend (StoveHttpResponse) -> Unit,
    ): DefaultHttpSystem = httpClient.send(uri) { request ->
        token.map { request.setHeader(Headers.Authentication, Headers.bearer(it)) }
        body.fold(
            ifEmpty = { request.POST(BodyPublishers.noBody()) },
            ifSome = { request.POST(BodyPublishers.ofString(json.serialize(it))) }
        )
    }.let { expect(StoveHttpResponse(it.statusCode(), it.headers().map())); this }

    override suspend fun <TExpected : Any> get(
        uri: String,
        clazz: KClass<TExpected>,
        token: Option<String>,
        expect: suspend (TExpected) -> Unit,
    ): DefaultHttpSystem = httpClient.send(uri) { request ->
        token.map { request.setHeader(Headers.Authentication, Headers.bearer(it)) }
        request.GET()
    }.let { expect(deserialize(it, clazz)); this }

    override suspend fun <TExpected : Any> getMany(
        uri: String,
        clazz: KClass<TExpected>,
        token: Option<String>,
        expect: suspend (List<TExpected>) -> Unit,
    ): DefaultHttpSystem = httpClient.send(uri) { request ->
        token.map { request.setHeader(Headers.Authentication, Headers.bearer(it)) }
        request.GET()
    }.let { expect(json.deserialize(it.body())); this }

    override suspend fun getResponse(
        uri: String,
        token: Option<String>,
        expect: suspend (StoveHttpResponse) -> Unit,
    ): DefaultHttpSystem = httpClient.send(uri) { request ->
        token.map { request.setHeader(Headers.Authentication, Headers.bearer(it)) }
        request
    }.let { expect(StoveHttpResponse(it.statusCode(), it.headers().map())); this }

    override fun then(): TestSystem = testSystem

    private suspend fun HttpClient.send(
        uri: String,
        configureRequest: (request: HttpRequest.Builder) -> HttpRequest.Builder,
    ): HttpResponse<ByteArray> {
        val requestBuilder = HttpRequest.newBuilder()
            .uri(relative(uri))
            .setHeader(Headers.ContentType, MediaType.ApplicationJson)
        return sendAsync(configureRequest(requestBuilder).build(), BodyHandlers.ofByteArray()).await()
    }

    private fun relative(uri: String): URI = URI.create(testSystem.baseUrl).resolve(uri)

    private fun httpClient(): HttpClient {
        val builder = HttpClient.newBuilder()
        builder.connectTimeout(Duration.ofSeconds(5))
        builder.followRedirects(ALWAYS)
        builder.version(HTTP_2)
        return builder.build()
    }

    private fun <TExpected : Any> deserialize(
        it: HttpResponse<ByteArray>,
        clazz: KClass<TExpected>,
    ): TExpected = when {
        clazz.java.isAssignableFrom(String::class.java) -> String(it.body()) as TExpected
        else -> json.deserialize(it.body(), clazz)
    }

    override fun close() {}

    companion object {
        object MediaType {
            const val ApplicationJson = "application/json"
        }

        object Headers {
            const val ContentType = "Content-Type"
            const val Authentication = "Authentication "

            fun bearer(token: String) = "Bearer $token"
        }

        fun DefaultHttpSystem.client(): HttpClient = this.httpClient
    }
}
