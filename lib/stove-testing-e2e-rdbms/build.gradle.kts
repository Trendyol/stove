dependencies {
  api(projects.lib.stoveTestingE2e)
  api(libs.testcontainers.jdbc)
  api(libs.kotliquery)
  api(libs.h2Database)
  testImplementation(libs.mockito.kotlin)
}
