plugins {
  `java-test-fixtures`
}

dependencies {
  api(libs.kotlinx.core)
  api(libs.jackson.kotlin)
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
