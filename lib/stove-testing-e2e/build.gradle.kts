plugins {
  `java-test-fixtures`
  alias(libs.plugins.kotlinx.serialization)
}

dependencies {
  api(libs.kotlinx.core)
  api(libs.bundles.jackson)
  api(libs.google.gson)
  api(libs.kotlinx.serialization.json)
  api(libs.testcontainers) {
    version {
      require(libs.testcontainers.asProvider().get().version!!)
    }
  }

  testImplementation(libs.kotest.arrow)
  testFixturesImplementation(libs.kotest.runner.junit5)
}

val javaComponent = components["java"] as AdhocComponentWithVariants
javaComponent.withVariantsFromConfiguration(configurations["testFixturesApiElements"]) { skip() }
javaComponent.withVariantsFromConfiguration(configurations["testFixturesRuntimeElements"]) { skip() }
