dependencies {
  api(projects.lib.stoveTestingE2eRdbms)
  api(libs.testcontainers.postgres)
  api(libs.postgresql)
  testImplementation(libs.logback.classic)
}
