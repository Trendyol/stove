package com.trendyol.stove.system.abstractions

/**
 * Marks the [TestSystem] as ready after it is started.
 * @author Oguzhan Soykan
 */
interface ReadyStove {
  suspend fun run()
}
