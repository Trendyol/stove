dependencies {
  api(projects.lib.stoveTestingE2eRdbms)
  api(libs.testcontainers.postgres)
  api(libs.postgresql)
  testImplementation(libs.logback.classic)
}

tasks.test.configure {
  systemProperty("kotest.framework.config.fqn", "com.trendyol.stove.testing.e2e.rdbms.postgres.Stove")
}
