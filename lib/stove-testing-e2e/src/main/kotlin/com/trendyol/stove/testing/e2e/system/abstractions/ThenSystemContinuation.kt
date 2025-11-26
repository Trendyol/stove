package com.trendyol.stove.testing.e2e.system.abstractions

import com.trendyol.stove.testing.e2e.system.TestSystem

/**
 * Enables method chaining between different system assertions in the DSL.
 *
 * All [PluggedSystem]s implement this interface, allowing fluent switching
 * between different systems during test assertions.
 *
 * ## Chaining Example
 *
 * ```kotlin
 * TestSystem.validate {
 *     http {
 *         postAndExpectBodilessResponse("/orders", body = order.some()) {
 *             it.status shouldBe 201
 *         }
 *     }
 *         .then()  // Switch back to TestSystem context
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
 * The `then()` method returns the [TestSystem], enabling continued assertions:
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
 * @property testSystem The parent test system for continuation.
 * @see PluggedSystem
 * @author Oguzhan Soykan
 */
interface ThenSystemContinuation {
  val testSystem: TestSystem

  /**
   * Returns the [TestSystem] to continue with other system assertions.
   *
   * @return The parent test system.
   */
  fun then(): TestSystem = testSystem

  /**
   * Executes an action only if dependencies are not set to keep running.
   *
   * This is useful for cleanup actions that should be skipped when
   * containers are reused across test runs (development mode).
   *
   * @param action The suspend action to conditionally execute.
   */
  suspend fun executeWithReuseCheck(action: suspend () -> Unit) {
    if (testSystem.options.keepDependenciesRunning) {
      return
    }
    action()
  }
}
