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

  // Compile-time dependencies for extensions (compileOnly since they're only needed for extension classes)
  // kotest-framework-engine contains TestCase, TestResult, and listener interfaces
  compileOnly(libs.kotest.framework.engine) // For Kotest extension (TestCase, TestResult, BeforeTestListener, AfterTestListener)
  compileOnly(libs.junit.jupiter.api) // For JUnit extension

  testImplementation(libs.kotest.arrow)
  testImplementation(libs.kotest.runner.junit5)
  testImplementation(libs.kotest.framework.engine)
  testImplementation(libs.kotest.assertions.core)
  testFixturesImplementation(libs.kotest.runner.junit5)
}

val javaComponent = components["java"] as AdhocComponentWithVariants
javaComponent.withVariantsFromConfiguration(configurations["testFixturesApiElements"]) { skip() }
javaComponent.withVariantsFromConfiguration(configurations["testFixturesRuntimeElements"]) { skip() }
