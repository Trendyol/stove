dependencies {
  api(projects.lib.stoveTestingE2e)
  api(libs.testcontainers.jdbc)
  api(libs.exposed.core)
  api(libs.exposed.jdbc)
  testImplementation(libs.mockito.kotlin)
}
