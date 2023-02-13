package com.trendyol.stove.testing.e2e.system.abstractions

/**
 * Marks the options as the SystemOptions
 */
interface SystemOptions

/**
 * Marks the configuration as ExposedConfiguration to the outside of the API.
 */
interface ExposedConfiguration

/**
 * Interface that defines the function of how configurations can be exposed
 */
interface ConfiguresExposedConfiguration<T : ExposedConfiguration> {
    val configureExposedConfiguration: (T) -> List<String>
}
