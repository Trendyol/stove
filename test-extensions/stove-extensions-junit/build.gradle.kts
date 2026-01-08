dependencies {
  api(projects.lib.stove)
  api(libs.junit.jupiter.api)
}

dependencies {
  testImplementation(projects.lib.stoveHttp)
  testImplementation(projects.lib.stoveWiremock)
  testImplementation(libs.kotest.runner.junit5)
  testImplementation(libs.kotest.assertions.core)
  testImplementation(libs.logback.classic)
  testImplementation(testFixtures(projects.lib.stove))
}
