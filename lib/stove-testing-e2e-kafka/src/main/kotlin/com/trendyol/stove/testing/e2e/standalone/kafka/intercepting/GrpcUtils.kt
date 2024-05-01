package com.trendyol.stove.testing.e2e.standalone.kafka.intercepting

import com.squareup.wire.*
import com.trendyol.stove.testing.e2e.standalone.kafka.StoveKafkaObserverServiceClient
import okhttp3.*
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

object GrpcUtils {
  private val getClient = {
    OkHttpClient.Builder()
      .protocols(listOf(Protocol.H2_PRIOR_KNOWLEDGE))
      .callTimeout(30.seconds.toJavaDuration())
      .readTimeout(30.seconds.toJavaDuration())
      .writeTimeout(30.seconds.toJavaDuration())
      .connectTimeout(30.seconds.toJavaDuration())
      .build()
  }

  fun createClient(onPort: String): StoveKafkaObserverServiceClient = GrpcClient.Builder()
    .client(getClient())
    .baseUrl("http://0.0.0.0:$onPort".toHttpUrl())
    .build()
    .create<StoveKafkaObserverServiceClient>()
}
