package com.trendyol.stove

/**
 * Marks Stove APIs that are available for early use but may change between releases.
 */
@MustBeDocumented
@RequiresOptIn(
  message = "This Stove API is experimental and may change between releases.",
  level = RequiresOptIn.Level.ERROR
)
@Retention(AnnotationRetention.BINARY)
@Target(
  AnnotationTarget.CLASS,
  AnnotationTarget.FUNCTION,
  AnnotationTarget.PROPERTY,
  AnnotationTarget.CONSTRUCTOR,
  AnnotationTarget.TYPEALIAS
)
annotation class ExperimentalStoveApi
