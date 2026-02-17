plugins {
  `java-test-fixtures`
  alias(libs.plugins.kotlinx.serialization)
}

dependencies {
  api(libs.arrow.core)
  api(libs.kotlinx.core)
  api(libs.jackson.kotlin)
  api(libs.jackson.databind)
  api(libs.google.gson)
  api(libs.kotlinx.serialization.json)
  api(libs.testcontainers) {
    version {
      require(libs.testcontainers.asProvider().get().version!!)
    }
  }
  // OTel API for setting trace context and baggage so the Java Agent
  // creates child spans with Stove's trace ID and propagates test metadata.
  // No-op when agent is not present.
  implementation(libs.opentelemetry.api)
}

dependencies {
  testImplementation(libs.kotest.arrow)
  testImplementation(libs.kotest.runner.junit5)
  testImplementation(libs.kotest.framework.engine)
  testImplementation(libs.kotest.assertions.core)
  testFixturesImplementation(libs.kotest.runner.junit5)
}

val javaComponent = components["java"] as AdhocComponentWithVariants
javaComponent.withVariantsFromConfiguration(configurations["testFixturesApiElements"]) { skip() }
javaComponent.withVariantsFromConfiguration(configurations["testFixturesRuntimeElements"]) { skip() }
