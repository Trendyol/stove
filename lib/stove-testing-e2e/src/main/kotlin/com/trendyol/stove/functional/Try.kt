@file:Suppress("unused", "TooGenericExceptionCaught")

package com.trendyol.stove.functional

import arrow.core.*
import arrow.core.Either.Left
import arrow.core.Either.Right

// https://github.com/sczerwinski/kotlin-util

/**
 * Representation of an operation that might successfully return a value or throw an exception.
 *
 * An instance of [Try] may be either a [Success] or a [Failure].
 *
 * This type is based on `scala.util.Try`.
 *
 * _Note: Only non-fatal exceptions are caught by the combinators on `Try` (see [NonFatal]). Serious
 * system errors, on the other hand, will be thrown._
 *
 * @param T Type of the value of a successful operation.
 */
sealed class Try<out T> {
  /**
   * Returns `true` if this is a [Success] or `false` if this is [Failure].
   *
   * @return `true` if this is a [Success] or `false` if this is [Failure].
   */
  abstract val isSuccess: Boolean

  /**
   * Returns `true` if this is a [Failure] or `false` if this is [Success].
   *
   * @return `true` if this is a [Failure] or `false` if this is [Success].
   */
  abstract val isFailure: Boolean

  /**
   * Returns a [Success] with an exception it this is a [Failure] or a [Failure] if this is a
   * [Success].
   *
   * @return A [Success] with an exception it this is a [Failure] or a [Failure] if this is a
   * [Success].
   */
  abstract val failed: Try<Throwable>

  /**
   * Gets the value of a [Success] or throw an exception from a [Failure].
   *
   * @return Value of a [Success].
   *
   * @throws Throwable If this is a [Failure].
   */
  abstract fun get(): T

  /**
   * Gets the value of a [Success] or `null` if this is a [Failure].
   *
   * @return Value of a [Success] or `null`.
   */
  abstract fun getOrNull(): T?

  /**
   * Runs [action] if this is a [Success]. Returns [Unit] without any action if this is a [Failure].
   *
   * @param action Action to be run on a value of a [Success].
   */
  inline fun forEach(action: (T) -> Unit) {
    if (isSuccess) action(get())
  }

  /**
   * Maps value of a [Success] using [transform] or returns the same [Try] if this is a [Failure].
   *
   * @param transform Function transforming value of a [Success].
   *
   * @return [Try] with a value mapped using [transform] or this object if this is a [Failure].
   */
  inline fun <R> map(transform: (T) -> R): Try<R> =
    when (this) {
      is Success -> Try { transform(value) }
      is Failure -> this
    }

  /**
   * Maps value of a [Success] to a new [Try] using [transform] or returns the same [Try] if this is
   * a [Failure].
   *
   * @param transform Function transforming value of a [Success] to a [Try].
   *
   * @return [Try] returned by [transform] or this object if this is a [Failure].
   */
  inline fun <R> flatMap(transform: (T) -> Try<R>): Try<R> =
    when (this) {
      is Success -> {
        try {
          transform(value)
        } catch (exception: Throwable) {
          if (NonFatal(exception)) Failure(exception) else throw exception
        }
      }

      is Failure -> {
        this
      }
    }

  /**
   * Returns the same [Success] if the [predicate] is satisfied for the value. Otherwise, returns a
   * [Failure].
   *
   * @param predicate Predicate function.
   *
   * @return The same [Success] if the [predicate] is satisfied for the value. Otherwise, returns a
   * [Failure].
   */
  inline fun filter(predicate: (T) -> Boolean): Try<T> =
    when (this) {
      is Success -> {
        try {
          if (predicate(value)) {
            this
          } else {
            throw NoSuchElementException("Predicate not satisfied for $value")
          }
        } catch (exception: Throwable) {
          if (NonFatal(exception)) Failure(exception) else throw exception
        }
      }

      is Failure -> {
        this
      }
    }

  /**
   * Returns the same [Success] if the [predicate] is not satisfied for the value. Otherwise,
   * returns a [Failure].
   *
   * @param predicate Predicate function.
   *
   * @return The same [Success] if the [predicate] is not satisfied for the value. Otherwise,
   * returns a [Failure].
   */
  inline fun filterNot(predicate: (T) -> Boolean): Try<T> =
    when (this) {
      is Success -> {
        try {
          if (!predicate(value)) {
            this
          } else {
            throw NoSuchElementException("Predicate not satisfied for $value")
          }
        } catch (exception: Throwable) {
          if (NonFatal(exception)) Failure(exception) else throw exception
        }
      }

      is Failure -> {
        this
      }
    }

  /**
   * Returns the same [Success] cast to type [R] if it is [R]. Otherwise, returns a [Failure].
   *
   * @param R Required type of the optional value.
   *
   * @return The same [Success] cast to type [R] if it is [R]. Otherwise, returns a [Failure].
   */
  inline fun <reified R> filterIsInstance(): Try<R> =
    when (this) {
      is Success -> {
        try {
          if (value is R) {
            Success<R>(value)
          } else {
            throw NoSuchElementException("Not an instance of ${R::class}")
          }
        } catch (exception: Throwable) {
          if (NonFatal(exception)) Failure(exception) else throw exception
        }
      }

      is Failure -> {
        this
      }
    }

  /**
   * Returns the same [Success] if the [predicate] is satisfied for the value. Otherwise, returns a
   * [Failure] containing the given [throwable].
   *
   * @param predicate Predicate function.
   * @param throwable Function providing a throwable to be used when the [predicate] is not
   * satisfied.
   *
   * @return The same [Success] if the [predicate] is satisfied for the value. Otherwise, returns a
   * [Failure] containing the given [throwable].
   *
   * @since 1.2
   */
  inline fun filterOrElse(
    predicate: (T) -> Boolean,
    throwable: (T) -> Throwable
  ): Try<T> =
    when (this) {
      is Success -> {
        try {
          if (predicate(value)) this else throw throwable(value)
        } catch (exception: Throwable) {
          if (NonFatal(exception)) Failure(exception) else throw exception
        }
      }

      is Failure -> {
        this
      }
    }

  /**
   * Transforms a [Success] using [successTransform] or a [Failure] using [failureTransform].
   *
   * @param successTransform Function transforming value of a [Success] to a new [Try].
   * @param failureTransform Function transforming exception from a [Failure] to a new [Try].
   *
   * @return Result of applying [successTransform] on [Success] or [failureTransform] on [Failure] .
   */
  inline fun <R> fold(
    successTransform: (T) -> R,
    failureTransform: (Throwable) -> R
  ): R =
    when (this) {
      is Success -> {
        try {
          successTransform(value)
        } catch (exception: Throwable) {
          if (NonFatal(exception)) failureTransform(exception) else throw exception
        }
      }

      is Failure -> {
        failureTransform(exception)
      }
    }

  /**
   * Transforms a [Success] using [successTransform] or a [Failure] using [failureTransform].
   *
   * @param successTransform Function transforming value of a [Success] to a new [Try].
   * @param failureTransform Function transforming exception from a [Failure] to a new [Try].
   *
   * @return New [Try] being a result of a transformation of a [Success] with [successTransform] or
   * a [Failure] with [failureTransform].
   */
  inline fun <R> transform(
    successTransform: (T) -> Try<R>,
    failureTransform: (Throwable) -> Try<R>
  ): Try<R> =
    try {
      when (this) {
        is Success -> successTransform(value)
        is Failure -> failureTransform(exception)
      }
    } catch (exception: Throwable) {
      if (NonFatal(exception)) Failure(exception) else throw exception
    }

  /**
   * Returns [Success] containing a `Pair` of values of this and [other] [Try] if both instances of
   * [Try] are [Success]. Otherwise, returns first [Failure].
   *
   * @param other Other [Try].
   *
   * @return [Success] containing a `Pair` of values of this and [other] [Try] if both instances of
   * [Try] are [Success]. Otherwise, returns first [Failure].
   *
   * @since 1.1
   */
  infix fun <R> zip(other: Try<R>): Try<Pair<T, R>> = Try { get() to other.get() }

  /**
   * Returns [Success] containing the result of applying [transform] to both values of this and
   * [other] [Try] if both instances of [Try] are [Success]. Otherwise, returns first [Failure].
   *
   * @param other Other [Try].
   * @param transform Function transforming values of both instances of [Success].
   *
   * @return [Success] containing the result of applying [transform] to both values of this and
   * [other] [Try] if both instances of [Try] are [Success]. Otherwise, returns first [Failure].
   *
   * @since 1.1
   */
  inline fun <T1, R> zip(
    other: Try<T1>,
    transform: (T, T1) -> R
  ): Try<R> = Try { transform(get(), other.get()) }

  /**
   * Converts this [Try] to [Either].
   *
   * @return [Left] if this is [Failure] or [Right] if this is [Success].
   */
  abstract fun toEither(): Either<Throwable, T>

  /**
   * Converts this [Try] to [Option].
   *
   * @return [None] if this is [Failure] or [Some] if this is [Success].
   */
  abstract fun toOption(): Option<T>

  companion object {
    /**
     * Creates a new [Try] based on the result of the [callable].
     *
     * @param callable A callable operation.
     *
     * @return An instance of [Success] or [Failure], depending on whether the operation.
     */
    inline operator fun <T> invoke(callable: () -> T): Try<T> =
      try {
        Success(callable())
      } catch (exception: Throwable) {
        if (NonFatal(exception)) Failure(exception) else throw exception
      }
  }
}

/**
 * Gets the value of a [Success] or [default] value if this is a [Failure].
 *
 * @param default Default value provider.
 *
 * @return Value of a [Success] or [default] value.
 */
inline fun <T> Try<T>.getOrElse(default: () -> T): T = if (isSuccess) get() else default()

/**
 * Returns this [Try] if this is a [Success] or [default] if this is a [Failure].
 *
 * @param default Default [Try] provider.
 *
 * @return This [Success] or [default].
 */
inline fun <T> Try<T>.orElse(default: () -> Try<T>): Try<T> =
  if (isSuccess) {
    this
  } else {
    try {
      default()
    } catch (exception: Throwable) {
      if (NonFatal(exception)) Failure(exception) else throw exception
    }
  }

/**
 * Transforms a nested [Try] to a not nested [Try].
 *
 * @return [Try] nested in a [Success] or this object if this is a [Failure].
 */
fun <T> Try<Try<T>>.flatten(): Try<T> =
  when (this) {
    is Success -> value
    is Failure -> this
  }

/**
 * Returns this [Try] if this is a [Success] or a [Try] created for the [rescue] operation if this
 * is a [Failure].
 *
 * @param rescue Function creating a new value from the exception to a [Failure].
 *
 * @return This [Try] if this is a [Success] or a [Try] created for the [rescue] operation if this
 * is a [Failure].
 */
inline fun <T> Try<T>.recover(rescue: (Throwable) -> T): Try<T> =
  when (this) {
    is Success -> {
      this
    }

    is Failure -> {
      try {
        Success(rescue(exception))
      } catch (exception: Throwable) {
        if (NonFatal(exception)) Failure(exception) else throw exception
      }
    }
  }

/**
 * Returns this [Try] if this is a [Success] or a [Try] created by the [rescue] function if this is
 * a [Failure].
 *
 * @param rescue Function creating a new [Try] from the exception to a [Failure].
 *
 * @return This [Try] if this is a [Success] or a [Try] created by the [rescue] function if this is
 * a [Failure].
 */
inline fun <T> Try<T>.recoverWith(rescue: (Throwable) -> Try<T>): Try<T> =
  when (this) {
    is Success -> {
      this
    }

    is Failure -> {
      try {
        rescue(exception)
      } catch (exception: Throwable) {
        if (NonFatal(exception)) Failure(exception) else throw exception
      }
    }
  }

/**
 * Returns the same [Success] if its value is not `null`. Otherwise, returns a [Failure].
 *
 * @return The same [Success] if its value is not `null`. Otherwise, returns a [Failure].
 */
fun <T> Try<T?>.filterNotNull(): Try<T> =
  when (this) {
    is Success -> {
      try {
        if (value != null) Success(value) else throw NoSuchElementException("Value is null")
      } catch (exception: Throwable) {
        if (NonFatal(exception)) Failure(exception) else throw exception
      }
    }

    is Failure -> {
      this
    }
  }

data class Success<out T>(
  val value: T
) : Try<T>() {
  override val isSuccess: Boolean
    get() = true

  override val isFailure: Boolean
    get() = false

  override val failed: Try<Throwable>
    get() = Failure(UnsupportedOperationException("Unsupported operation: Success::failed"))

  override fun get(): T = value

  override fun getOrNull(): T? = value

  override fun toEither(): Either<Throwable, T> = Right(value)

  override fun toOption(): Option<T> = Some(value)
}

data class Failure(
  val exception: Throwable
) : Try<Nothing>() {
  override val isSuccess: Boolean
    get() = false

  override val isFailure: Boolean
    get() = true

  override val failed: Try<Throwable>
    get() = Success(exception)

  override fun get(): Nothing = throw exception

  override fun getOrNull(): Nothing? = null

  override fun toEither(): Either<Throwable, Nothing> = Left(exception)

  override fun toOption(): Option<Nothing> = None
}
