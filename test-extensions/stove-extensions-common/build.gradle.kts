plugins {
  `java-test-fixtures`
}

dependencies {
  api(projects.lib.stove)
  api(projects.lib.stoveTracing)
}

dependencies {
  testFixturesImplementation(libs.kotest.assertions.core)
}

val javaComponent = components["java"] as AdhocComponentWithVariants
javaComponent.withVariantsFromConfiguration(configurations["testFixturesApiElements"]) { skip() }
javaComponent.withVariantsFromConfiguration(configurations["testFixturesRuntimeElements"]) { skip() }
