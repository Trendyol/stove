package com.trendyol.stove.testing.e2e.mongodb

import com.fasterxml.jackson.databind.ObjectMapper

fun mongodb(init: MongodbOptionsDsl.() -> Unit): MongodbSystemOptions = MongodbOptionsDsl(init).invoke()

@DslMarker
@Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE)
annotation class MongoOptionsDsl

@MongoOptionsDsl
class MongodbOptionsDsl internal constructor(private val init: MongodbOptionsDsl.() -> Unit) {
    private var options: MongodbSystemOptions = MongodbSystemOptions()

    fun defaultDatabase(name: String) {
        options =
            options.copy(
                databaseOptions =
                    options.databaseOptions.copy(
                        default = options.databaseOptions.default.copy(name = name)
                    )
            )
    }

    fun image(image: String) {
        options = options.copy(container = MongoContainerOptions(image = image))
    }

    fun registry(registry: String) {
        options = options.copy(container = MongoContainerOptions(registry = registry))
    }

    fun tag(tag: String) {
        options = options.copy(container = MongoContainerOptions(tag = tag))
    }

    fun exposedConfiguration(configureExposedConfiguration: (MongodbExposedConfiguration) -> List<String> = { _ -> listOf() }) {
        options = options.copy(configureExposedConfiguration = configureExposedConfiguration)
    }

    fun objectMapper(objectMapper: ObjectMapper) {
        options = options.copy(objectMapper = objectMapper)
    }

    internal operator fun invoke(): MongodbSystemOptions = init().let { options }
}
