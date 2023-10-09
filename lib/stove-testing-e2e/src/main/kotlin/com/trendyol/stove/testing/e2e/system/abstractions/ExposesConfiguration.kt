package com.trendyol.stove.testing.e2e.system.abstractions

/**
 * Marks the dependency which can expose the configuration that is needed to spin up the [ApplicationUnderTest]
 * The [configuration] is collected and passed to [ApplicationUnderTest.start]
 * For example:
 * If kafka has a configuration that exposes as `bootStrapServers` can pass this configuration to the application itself.
 * Our application probably depends on this configuration in either `application.yml`(for spring) or in any configuration structure.
 * For spring applications, let's say you have `kafka.bootStrapServers` in the `application.yml`, then KafkaSystem needs to expose
 * this configuration by implementing [ExposesConfiguration].
 *
 * @author Oguzhan Soykan
 */
interface ExposesConfiguration {
    /**
     * Gets the configurations that dependency exposes.
     * It is invoked after [RunAware.run], so docker instances are running at this stage.
     */
    fun configuration(): List<String>
}
