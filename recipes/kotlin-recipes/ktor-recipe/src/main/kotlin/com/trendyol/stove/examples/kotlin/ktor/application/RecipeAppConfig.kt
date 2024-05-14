package com.trendyol.stove.examples.kotlin.ktor.application

/**
 * Represents the main configuration
 */
data class RecipeAppConfig(
  val server: ServerConfig,
  val kafka: KafkaConfiguration,
  val mongo: MongoConfiguration
)

/**
 * Represents the configuration of the checker.
 */
data class ServerConfig(
  /**
   * Port of the server.
   */
  val port: Int = 8080,
  /**
   * Host of the server.
   */
  val host: String = "",
  val name: String
)

data class MongoConfiguration(
  val uri: String,
  val database: String
)

data class KafkaConfiguration(
  val bootstrapServers: String,
  val groupId: String,
  val requestTimeoutSeconds: Long = 30,
  val heartbeatIntervalSeconds: Long = 3,
  val sessionTimeoutSeconds: Long = 10,
  val autoCreateTopics: Boolean = true,
  val autoOffsetReset: String = "earliest",
  val interceptorClasses: List<String>,
  val topics: Map<String, Topic>
) {
  fun flattenInterceptorClasses(): String {
    return interceptorClasses.joinToString(",")
  }
}

data class Topic(
  val name: String,
  val retry: String,
  val deadLetter: String
)
