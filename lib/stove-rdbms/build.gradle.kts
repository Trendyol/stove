dependencies {
  api(projects.lib.stove)
  api(libs.testcontainers.jdbc)
  api(libs.kotliquery)
  api(libs.h2Database)
  testImplementation(libs.mockito.kotlin)
}
