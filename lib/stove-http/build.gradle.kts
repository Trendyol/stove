dependencies {
  api(projects.lib.stove)
  api(libs.ktor.client.core)
  api(libs.ktor.client.okhttp)
  api(libs.ktor.client.plugins.logging)
  api(libs.ktor.client.content.negotiation)
  api(libs.ktor.serialization.jackson.json)
  api(libs.ktor.client.websockets)
  implementation(libs.kotlinx.core)
  implementation(libs.kotlinx.io.reactor)
  implementation(libs.kotlinx.reactive)
  implementation(libs.kotlinx.jdk8)
}

dependencies {
  testImplementation(projects.lib.stoveWiremock)
  testImplementation(project(":test-extensions:stove-extensions-kotest"))
  testImplementation(libs.jackson.jsr310)
  testImplementation(testFixtures(projects.lib.stove))
  testImplementation(libs.logback.classic)
  testImplementation(libs.ktor.server.netty)
  testImplementation(libs.ktor.server.websockets)
}
