package com.trendyol.stove.system.abstractions

/**
 * Marker interface for typed keys used to register and look up multiple instances of the same system type.
 *
 * Define keys as singleton objects:
 * ```kotlin
 * object PaymentService : SystemKey
 * object OrderService : SystemKey
 * object AnalyticsDb : SystemKey
 * ```
 *
 * Use keys in registration and validation DSLs:
 * ```kotlin
 * // Registration
 * httpClient(PaymentService) { HttpClientSystemOptions(baseUrl = "https://pay.internal") }
 *
 * // Validation
 * http(PaymentService) { get<Payment>("/payments") { ... } }
 * ```
 *
 * A single key can be shared across protocols:
 * ```kotlin
 * httpClient(PaymentService) { ... }
 * grpc(PaymentService) { ... }
 * ```
 *
 * @see com.trendyol.stove.system.Stove.getOrRegister
 */
interface SystemKey

internal val UNSAFE_FILENAME_CHARS = Regex("[^a-zA-Z0-9._-]")

/**
 * Returns a display-safe, filesystem-safe name for a [SystemKey],
 * with fallbacks for anonymous classes.
 */
fun keyDisplayName(key: SystemKey): String =
  (key::class.simpleName ?: key::class.qualifiedName ?: "anonymous-key-${System.identityHashCode(key)}")
    .replace(UNSAFE_FILENAME_CHARS, "-")
