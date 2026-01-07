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
  private val getClient = { scope: CoroutineScope ->
    OkHttpClient
      .Builder()
      .protocols(listOf(Protocol.H2_PRIOR_KNOWLEDGE))
      .callTimeout(30.seconds.toJavaDuration())
      .readTimeout(30.seconds.toJavaDuration())
      .writeTimeout(30.seconds.toJavaDuration())
      .connectTimeout(30.seconds.toJavaDuration())
      .dispatcher(Dispatcher(scope.asExecutorService))
      .build()
  }

  fun createClient(onPort: String, scope: CoroutineScope): StoveKafkaObserverServiceClient = GrpcClient
    .Builder()
    .client(getClient(scope))
    .baseUrl(onLoopback(onPort))
    .build()
    .create<StoveKafkaObserverServiceClient>()

  private fun onLoopback(port: String): GrpcHttpUrl = "http://${InetAddress.getLoopbackAddress().hostAddress}:$port".toHttpUrl()
}
