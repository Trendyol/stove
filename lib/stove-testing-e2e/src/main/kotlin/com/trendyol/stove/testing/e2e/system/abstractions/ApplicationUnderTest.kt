package com.trendyol.stove.testing.e2e.system.abstractions

/**
 * The application that test system runs against. Usually, a spring or generic application that has a lifecycle
 * @author Oguzhan Soykan
 */
interface ApplicationUnderTest<TContext : Any> {
    /**
     * Starts the application with the given parameters. [configurations] usually represents the CLI-like arguments.
     * For example:
     *
     * ```shell
     * java -jar app.jar createDb=true migrate=true
     * ```
     * Here, [configurations] becomes:
     * ```kotlin
     * listOf("createDb=true", "migrate=true")
     * ```
     */
    suspend fun start(configurations: List<String>): TContext

    /**
     * Stops the application
     */
    suspend fun stop()
}
