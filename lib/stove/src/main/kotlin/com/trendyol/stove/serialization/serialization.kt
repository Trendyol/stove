package com.trendyol.stove.serialization

import arrow.core.*

/**
 * Unified serialization/deserialization interface for Stove's test infrastructure.
 *
 * Stove uses this interface internally for JSON handling in HTTP responses, Kafka messages,
 * document databases, and more. You can configure which implementation to use (Jackson, Gson,
 * or Kotlinx Serialization) to match your application's serialization setup.
 *
 * ## Available Implementations
 *
 * - [StoveSerde.jackson] - Jackson ObjectMapper (default)
 * - [StoveSerde.gson] - Google Gson
 * - [StoveSerde.kotlinx] - Kotlinx Serialization
 *
 * ## Configuration Example
 *
 * ```kotlin
 * // Configure Kafka to use the same ObjectMapper as your application
 * kafka {
 *     stoveKafkaObjectMapperRef = myApplicationObjectMapper
 *     KafkaSystemOptions { cfg ->
 *         listOf("kafka.bootstrapServers=${cfg.bootstrapServers}")
 *     }
 * }
 *
 * // Configure HTTP client with custom content converter
 * httpClient {
 *     HttpClientSystemOptions(
 *         baseUrl = "http://localhost:8080",
 *         contentConverter = JacksonConverter(myObjectMapper)
 *     )
 * }
 * ```
 *
 * ## Custom Serde Implementation
 *
 * ```kotlin
 * object MyCustomSerde : StoveSerde<Any, String> {
 *     override fun serialize(value: Any): String = mySerialize(value)
 *
 *     override fun <T : Any> deserialize(value: String, clazz: Class<T>): T =
 *         myDeserialize(value, clazz)
 * }
 * ```
 *
 * @param TIn The base type of objects that can be serialized (typically `Any`).
 * @param TOut The serialized format type (`String` for JSON, `ByteArray` for binary).
 */
interface StoveSerde<TIn : Any, TOut : Any> {
  /**
   * Serializes an object to the target format.
   *
   * @param value The object to serialize.
   * @return The serialized representation.
   * @throws StoveSerdeProblem.BecauseOfSerialization if serialization fails.
   */
  fun serialize(value: TIn): TOut

  /**
   * Deserializes data into the specified type.
   *
   * @param value The serialized data.
   * @param clazz The target class to deserialize into.
   * @return The deserialized object.
   * @throws StoveSerdeProblem.BecauseOfDeserialization if deserialization fails.
   */
  fun <T : TIn> deserialize(value: TOut, clazz: Class<T>): T

  /**
   * Deserializes data with error handling via [Either].
   *
   * Use this when you want to handle deserialization failures gracefully
   * without exceptions.
   *
   * ```kotlin
   * val result = serde.deserializeEither(json, User::class.java)
   * result.fold(
   *     ifLeft = { error -> println("Failed: ${error.message}") },
   *     ifRight = { user -> println("Success: ${user.name}") }
   * )
   * ```
   *
   * @param value The serialized data.
   * @param clazz The target class.
   * @return Either a [StoveSerdeProblem] or the deserialized object.
   */
  fun <T : TIn> deserializeEither(value: TOut, clazz: Class<T>): Either<StoveSerdeProblem, T> = Either
    .catch { deserialize(value, clazz) }
    .mapLeft { StoveSerdeProblem.BecauseOfDeserialization(it.message ?: "Deserialization failed", it) }

  /**
   * Companion object providing default serde implementations and utility functions.
   */
  companion object {
    /**
     * Jackson-based serialization using [com.fasterxml.jackson.databind.ObjectMapper].
     *
     * This is the default and most commonly used implementation.
     *
     * ```kotlin
     * val mapper = StoveSerde.jackson.default
     * val json = mapper.serialize(myObject)
     * val obj = mapper.deserialize<MyClass>(json)
     * ```
     */
    val jackson = StoveJackson

    /**
     * Gson-based serialization using [com.google.gson.Gson].
     *
     * ```kotlin
     * val gson = StoveSerde.gson.default
     * val json = gson.serialize(myObject)
     * ```
     */
    val gson = StoveGson

    /**
     * Kotlinx Serialization-based implementation.
     *
     * Requires classes to be annotated with `@Serializable`.
     *
     * ```kotlin
     * @Serializable
     * data class User(val name: String)
     *
     * val json = StoveSerde.kotlinx.default.serialize(user)
     * ```
     */
    val kotlinx = StoveKotlinx

    /**
     * Deserializes [ByteArray] data using reified type parameter.
     *
     * ```kotlin
     * val user: User = serde.deserialize(bytes)
     * ```
     */
    inline fun <reified T : Any> StoveSerde<Any, ByteArray>.deserialize(
      value: ByteArray
    ): T = deserialize(value, T::class.java)

    /**
     * Deserializes [ByteArray] data, returning [None] on failure.
     *
     * ```kotlin
     * val userOption: Option<User> = serde.deserializeOption(bytes)
     * userOption.onSome { user -> println(user.name) }
     * ```
     */
    inline fun <reified T : Any> StoveSerde<Any, ByteArray>.deserializeOption(
      value: ByteArray
    ): Option<T> = deserializeEither(value, T::class.java).getOrNone()

    /**
     * Deserializes [String] data using reified type parameter.
     *
     * ```kotlin
     * val user: User = serde.deserialize(jsonString)
     * ```
     */
    inline fun <reified T : Any> StoveSerde<Any, String>.deserialize(
      value: String
    ): T = deserialize(value, T::class.java)

    /**
     * Deserializes [String] data, returning [None] on failure.
     *
     * ```kotlin
     * val userOption: Option<User> = serde.deserializeOption(jsonString)
     * ```
     */
    inline fun <reified T : Any> StoveSerde<Any, String>.deserializeOption(value: String): Option<T> =
      deserializeEither(value, T::class.java).getOrNone()
  }

  /**
   * Sealed class hierarchy for serialization/deserialization errors.
   *
   * These exceptions provide structured error information when JSON operations fail.
   */
  sealed class StoveSerdeProblem(
    message: String,
    cause: Throwable? = null
  ) : RuntimeException(message, cause) {
    /**
     * Error during serialization (object to JSON/bytes).
     */
    class BecauseOfSerialization(
      message: String,
      cause: Throwable? = null
    ) : StoveSerdeProblem(message, cause)

    /**
     * Error during deserialization (JSON/bytes to object).
     */
    class BecauseOfDeserialization(
      message: String,
      cause: Throwable? = null
    ) : StoveSerdeProblem(message, cause)

    /**
     * Deserialization failed but a specific type was expected.
     *
     * Used when asserting message types in Kafka or document types in databases.
     */
    class BecauseOfDeserializationButExpected(
      message: String,
      cause: Throwable? = null
    ) : StoveSerdeProblem(message, cause)
  }
}
