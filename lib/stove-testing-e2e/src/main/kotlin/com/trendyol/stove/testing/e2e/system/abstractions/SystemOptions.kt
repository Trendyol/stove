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

/**
 * Interface for system options that represent externally provided instances
 * instead of testcontainers.
 *
 * This interface is implemented by `Provided*SystemOptions` classes that extend
 * the base system options. The provided options hold a non-nullable configuration
 * for the external instance.
 *
 * @param TConfig The type of exposed configuration for this system
 */
interface ProvidedSystemOptions<TConfig : ExposedConfiguration> {
  /**
   * The configuration for the provided (external) instance.
   * This is non-nullable because provided options always have a configuration.
   */
  val providedConfig: TConfig

  /**
   * Whether to run migrations when using a provided instance.
   */
  val runMigrationsForProvided: Boolean
}
