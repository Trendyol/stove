package com.trendyol.stove.system.abstractions

/**
 * An abstraction for a system that can be validated after each test or any given moment.
 * @author Oguzhan Soykan
 */
interface ValidatedSystem {
  /**
   * System that validates itself at any given moment
   * Each system needs to implement its validation logic
   */
  suspend fun validate()
}
