dependencies {
  testImplementation(testFixtures(projects.testExtensions.stoveExtensionsCommon))
  api(projects.testExtensions.stoveExtensionsCommon)
  api(libs.cucumber.java)
  api(libs.cucumber.plugin)
  implementation(libs.kotlin.reflect)
  testImplementation(libs.junit.platform.testkit)
  testImplementation(projects.testExtensions.stoveExtensionsJunit)
  testImplementation(projects.testExtensions.stoveExtensionsKotest)
  testRuntimeOnly(libs.junit.jupiter.engine)
  testImplementation(libs.cucumber.engine)
  testImplementation(libs.logback.classic)
  testRuntimeOnly(libs.jackson.jdk8)
}

tasks.test {
  exclude("**/Mixed*Fixture*")
  useJUnitPlatform { includeEngines("kotest") }
}
