package com.trendyol.stove.testing.e2e.mongodb

import com.trendyol.stove.testing.e2e.system.annotations.StoveDsl

@StoveDsl
fun mongodb(init: MongodbOptionsDsl.() -> Unit): MongodbSystemOptions = MongodbOptionsDsl(init).invoke()

@StoveDsl
class MongodbOptionsDsl internal constructor(private val init: MongodbOptionsDsl.() -> Unit) {
  private var options: MongodbSystemOptions = MongodbSystemOptions(configureExposedConfiguration = { listOf() })

  fun defaultDatabase(name: String) {
    options = options.copy(
      databaseOptions = options.databaseOptions.copy(
        default = options.databaseOptions.default.copy(name = name)
      )
    )
  }

  fun image(image: String) {
    options = options.copy(container = options.container.copy(image = image))
  }

  fun registry(registry: String) {
    options = options.copy(container = options.container.copy(registry = registry))
  }

  fun tag(tag: String) {
    options = options.copy(container = options.container.copy(tag = tag))
  }

  fun exposedConfiguration(configureExposedConfiguration: (MongodbExposedConfiguration) -> List<String>) {
    options = options.copy(configureExposedConfiguration = configureExposedConfiguration)
  }

  internal operator fun invoke(): MongodbSystemOptions {
    return init().let { options }
  }
}
