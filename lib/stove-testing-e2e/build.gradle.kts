plugins {
  `java-test-fixtures`
  alias(libs.plugins.kotlinx.serialization)
}

dependencies {
  api(libs.kotlinx.core)
  api(libs.jackson.kotlin)
  api(libs.jackson.arrow)
  api(libs.google.gson)
  api(libs.kotlinx.serialization)
  api(libs.testcontainers) {
    version {
      require(libs.testcontainers.asProvider().get().version!!)
    }
  }

  testFixturesImplementation(libs.kotest.framework.api)
}

val javaComponent = components["java"] as AdhocComponentWithVariants
javaComponent.withVariantsFromConfiguration(configurations["testFixturesApiElements"]) { skip() }
javaComponent.withVariantsFromConfiguration(configurations["testFixturesRuntimeElements"]) { skip() }
