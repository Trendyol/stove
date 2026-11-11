dependencies {
  implementation(libs.ktor.server)
  implementation(libs.ktor.server.cio)

  testImplementation(projects.testExtensions.stoveExtensionsCucumber)
  testImplementation(projects.starters.ktor.stoveKtor)
  testImplementation(projects.lib.stoveHttp)
  testImplementation(libs.cucumber.engine)
  testImplementation(libs.cucumber.picocontainer)
  testImplementation(libs.junit.platform.suite)
  testImplementation(libs.logback.classic)
  testRuntimeOnly(libs.jackson.jdk8)
}

// Only the suite engine discovers the suite; its selector delegates features to Cucumber.
// Keeping the Cucumber engine out of top-level discovery prevents duplicate execution.
tasks.test {
  useJUnitPlatform { includeEngines("junit-platform-suite") }
  // Exercise the hint workaround even on fast machines if it is accidentally removed.
  systemProperty("cucumber.glue.hint.threshold", "PT0S")
  systemProperty("cucumber.filter.tags", providers.gradleProperty("cucumber.tags").getOrElse("not @failure-demo"))
}
