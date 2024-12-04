package com.trendyol.stove.testing.e2e.serialization

import arrow.core.*

/**
 * Generic interface for serialization and deserialization operations.
 *
 * @param TIn The type of input object to be serialized
 * @param TOut The type of output format after serialization
 */
interface StoveSerde<TIn : Any, TOut : Any> {
  /**
   * Serializes an input object into the target format.
   *
   * @param value The input object to serialize
   * @return The serialized output
   */

  fun serialize(value: TIn): TOut

  /**
   * Deserializes data from the target format into the specified type.
   *
   * @param value The serialized data to deserialize
   * @param clazz The target class to deserialize into
   * @return The deserialized object
   */
  fun <T : TIn> deserialize(value: TOut, clazz: Class<T>): T

  /**
   * Deserializes data from the target format into the specified type.
   * Returns an [Either] to indicate success or failure.
   * @param value The serialized data to deserialize
   * @param clazz The target class to deserialize into
   * @return The deserialized object or a [StoveSerdeProblem]
   */
  fun <T : TIn> deserializeEither(value: TOut, clazz: Class<T>): Either<StoveSerdeProblem, T> = Either
    .catch { deserialize(value, clazz) }
    .mapLeft { StoveSerdeProblem.BecauseOfDeserialization(it.message ?: "Deserialization failed", it) }

  /**
   * Companion object containing default configurations and utility functions
   */
  companion object {
    val jackson = StoveJackson

    val gson = StoveGson

    val kotlinx = StoveKotlinx

    /**
     * Deserializes data from the target format into the specified type.
     */
    inline fun <reified T : Any> StoveSerde<Any, ByteArray>.deserialize(
      value: ByteArray
    ): T = deserialize(value, T::class.java)

    /**
     * Deserializes data from the target format into the specified type.
     * Returns a [None] if deserialization fails.
     */
    inline fun <reified T : Any> StoveSerde<Any, ByteArray>.deserializeOption(
      value: ByteArray
    ): Option<T> = deserializeEither(value, T::class.java).getOrNone()

    /**
     * Deserializes data from the target format into the specified type.
     */
    inline fun <reified T : Any> StoveSerde<Any, String>.deserialize(
      value: String
    ): T = deserialize(value, T::class.java)

    /**
     * Deserializes data from the target format into the specified type.
     * Returns a [None] if deserialization fails.
     */
    inline fun <reified T : Any> StoveSerde<Any, String>.deserializeOption(value: String): Option<T> =
      deserializeEither(value, T::class.java).getOrNone()
  }

  sealed class StoveSerdeProblem(
    message: String,
    cause: Throwable? = null
  ) : RuntimeException(message, cause) {
    class BecauseOfSerialization(
      message: String,
      cause: Throwable? = null
    ) : StoveSerdeProblem(message, cause)

    class BecauseOfDeserialization(
      message: String,
      cause: Throwable? = null
    ) : StoveSerdeProblem(message, cause)

    class BecauseOfDeserializationButExpected(
      message: String,
      cause: Throwable? = null
    ) : StoveSerdeProblem(message, cause)
  }
}
