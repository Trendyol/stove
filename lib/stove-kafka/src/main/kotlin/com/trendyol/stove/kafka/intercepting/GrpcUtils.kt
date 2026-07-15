@file:Suppress("HttpUrlsUsage")

package com.trendyol.stove.kafka.intercepting

import com.squareup.wire.*
import com.trendyol.stove.kafka.*
import kotlinx.coroutines.CoroutineScope
import okhttp3.*
import java.net.InetAddress
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

object GrpcUtils {
  private fun httpClient(scope: CoroutineScope): OkHttpClient = OkHttpClient
    .Builder()
    .protocols(listOf(Protocol.H2_PRIOR_KNOWLEDGE))
    .callTimeout(30.seconds.toJavaDuration())
    .readTimeout(30.seconds.toJavaDuration())
    .writeTimeout(30.seconds.toJavaDuration())
    .connectTimeout(30.seconds.toJavaDuration())
    .dispatcher(Dispatcher(scope.asExecutorService))
    .build()

  fun createClient(onPort: String, scope: CoroutineScope): StoveKafkaObserverServiceClient =
    createClientHandle(onPort, scope).client

  internal fun createClientHandle(onPort: String, scope: CoroutineScope): StoveKafkaObserverClientHandle {
    val httpClient = httpClient(scope)
    val client = GrpcClient
      .Builder()
      .client(httpClient)
      .baseUrl(onLoopback(onPort))
      .build()
      .create<StoveKafkaObserverServiceClient>()
    return StoveKafkaObserverClientHandle(client, httpClient)
  }

  private fun onLoopback(port: String): GrpcHttpUrl = "http://${InetAddress.getLoopbackAddress().hostAddress}:$port".toHttpUrl()
}

internal class StoveKafkaObserverClientHandle(
  val client: StoveKafkaObserverServiceClient,
  private val httpClient: OkHttpClient
) : AutoCloseable {
  override fun close() {
    httpClient.dispatcher.cancelAll()
    httpClient.connectionPool.evictAll()
  }
}
