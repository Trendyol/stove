dependencies {
  api(projects.lib.stoveTestingE2e)
  api(libs.wiremock.standalone)
  api(libs.caffeine)
}

tasks.test.configure {
  systemProperty("kotest.framework.config.fqn", "com.trendyol.stove.testing.e2e.wiremock.Stove")
}
