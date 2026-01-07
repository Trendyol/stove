package com.trendyol.stove.system.abstractions

/**
 * Marks the [com.trendyol.stove.system.Stove] as ready after it is started.
 * @author Oguzhan Soykan
 */
interface ReadyStove {
  suspend fun run()
}
