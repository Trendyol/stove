package com.trendyol.stove.system.abstractions

import com.trendyol.stove.system.Stove

/**
 * Enables method chaining between different system assertions in the DSL.
 *
 * All [PluggedSystem]s implement this interface, allowing fluent switching
 * between different systems during test assertions.
 *
 * ## Chaining Example
 *
 * ```kotlin
 * stove {
 *     http {
 *         postAndExpectBodilessResponse("/orders", body = order.some()) {
 *             it.status shouldBe 201
 *         }
 *     }
 *         .then()  // Switch back to Stove context
 *         .also {
 *             kafka {
 *                 shouldBePublished<OrderCreatedEvent> {
 *                     actual.orderId == order.id
 *                 }
 *             }
 *         }
 * }
 * ```
 *
 * ## Fluent DSL Style
 *
 * The `then()` method returns [Stove], enabling continued assertions:
 *
 * ```kotlin
 * // All methods return their system, allowing chaining
 * couchbase {
 *     save(documentId, document)
 * }.then()
 *
 * http {
 *     get<Document>("/documents/$documentId") { doc ->
 *         doc shouldBe document
 *     }
 * }
 * ```
 *
 * @property stove The parent Stove instance for continuation.
 * @see PluggedSystem
 * @author Oguzhan Soykan
 */
interface ThenSystemContinuation {
  val stove: Stove

  /**
   * Returns [Stove] to continue with other system assertions.
   *
   * @return The parent Stove instance.
   */
  fun then(): Stove = stove

  /**
   * Executes an action only if dependencies are not set to keep running.
   *
   * This is useful for cleanup actions that should be skipped when
   * containers are reused across test runs (development mode).
   *
   * @param action The suspend action to conditionally execute.
   */
  suspend fun executeWithReuseCheck(action: suspend () -> Unit) {
    if (stove.options.keepDependenciesRunning) {
      return
    }
    action()
  }
}
