package com.trendyol.stove.testing.e2e.system.abstractions

/**
 * Marks the dependency which can be plugged into the [TestSystem]
 * @author Oguzhan Soykan
 */
interface PluggedSystem : AutoCloseable, ThenSystemContinuation
