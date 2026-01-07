@file:Suppress("unused")

package com.trendyol.stove.functional

import arrow.core.*

/** Extracts a [T] element if exists, otherwise throws [NoSuchElementException] */
fun <T> Option<T>.get(): T = this.getOrElse { throw NoSuchElementException("get() on Option<Nothing> does not exist") }

/**
 * Extracts an [Option] nested in the [Try] to a not nested [Option].
 *
 * @return [Option] nested in a [Success] or [None] if this is a [Failure].
 */
fun <T> Try<Option<T>>.flatten(): Option<T> =
  when (this) {
    is Success -> value
    is Failure -> None
  }

/**
 * Returns [Some] if this [Some] contains a [Success]. Otherwise, returns [None].
 *
 * @return [Some] if this [Some] contains a [Success]. Otherwise, returns [None].
 */
fun <T> Option<Try<T>>.flatten(): Option<T> = if (isNone()) None else get().toOption()

/**
 * Returns nested [List] if this is [Some]. Otherwise, returns an empty [List].
 *
 * @return Nested [List] if this is [Some]. Otherwise, returns an empty [List].
 */
fun <T> Option<Iterable<T>>.flatten(): List<T> = if (isNone()) emptyList() else get().toList()

/**
 * Returns [List] of values of each [Some] in this [Iterable].
 *
 * @return [List] of values of each [Some] in this [Iterable].
 */
fun <T> Iterable<Option<T>>.flatten(): List<T> = flatMap { it.toList() }

/**
 * Moves inner [Option] outside of the outer [Try].
 *
 * @return [Try] nested in an [Option] for an [Option] nested in a [Try].
 *
 * @since 1.4.0
 */
fun <T> Try<Option<T>>.evert(): Option<Try<T>> =
  when (this) {
    is Success -> value.map { Success(it) }
    is Failure -> Some(this)
  }

/**
 * Moves inner [Try] outside of the outer [Option].
 *
 * @return [Option] nested in a [Try] for a [Try] nested in an [Option].
 *
 * @since 1.4.0
 */
fun <T> Option<Try<T>>.evert(): Try<Option<T>> =
  when (this) {
    is Some -> value.map { Some(it) }
    is None -> Success(None)
  }
