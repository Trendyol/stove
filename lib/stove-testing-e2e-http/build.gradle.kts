dependencies {
  api(projects.lib.stoveTestingE2e)
  api(libs.ktor.client.core)
  api(libs.ktor.client.okhttp)
  api(libs.ktor.client.plugins.logging)
  api(libs.ktor.client.content.negotiation)
  api(libs.ktor.serialization.jackson.json)
  implementation(libs.kotlinx.core)
  implementation(libs.kotlinx.io.reactor)
  implementation(libs.kotlinx.reactive)
  implementation(libs.kotlinx.jdk8)
}

dependencies {
  testImplementation(projects.lib.stoveTestingE2eWiremock)
  testImplementation(libs.jackson.jsr310)
  testImplementation(testFixtures(projects.lib.stoveTestingE2e))
}

tasks.test.configure {
  systemProperty("kotest.framework.config.fqn", "com.trendyol.stove.testing.e2e.http.Stove")
}
