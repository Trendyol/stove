package com.trendyol.stove.testing.e2e.serialization

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
   * Companion object containing default configurations and utility functions
   */
  companion object {
    val jackson = StoveJackson

    val gson = StoveGson

    val kotlinx = StoveKotlinx

    inline fun <TIn : Any, TOut : Any, reified T : TIn> StoveSerde<TIn, TOut>.deserialize(
      value: TOut
    ): T = deserialize(value, T::class.java)
  }
}
