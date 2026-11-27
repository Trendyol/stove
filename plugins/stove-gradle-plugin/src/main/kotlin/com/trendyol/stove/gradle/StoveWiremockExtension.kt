package com.trendyol.stove.gradle

import org.gradle.api.Action
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import javax.inject.Inject

private val apiName = Regex("[a-z][a-zA-Z0-9]*")

abstract class StoveWiremockExtension @Inject constructor(private val objects: ObjectFactory) {
  val sourceSet: Property<String> = objects.property(String::class.java).convention("test")
  internal val contracts = linkedMapOf<String, StoveOpenApiSpec>()

  fun api(name: String, action: Action<StoveOpenApiSpec>) {
    require(name.matches(apiName)) { "Use a lower camel case API name: $name" }
    require(name !in contracts) { "Duplicate Stove WireMock API: $name" }
    contracts[name] = objects.newInstance(StoveOpenApiSpec::class.java).also(action::execute)
  }
}

abstract class StoveOpenApiSpec {
  abstract val specUrl: Property<String>
  abstract val specFile: RegularFileProperty
  abstract val specText: Property<String>
  abstract val schemaMappings: MapProperty<String, String>
}
