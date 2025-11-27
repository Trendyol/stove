plugins {
  alias(libs.plugins.spring.plugin)
}

dependencies {
  implementation(libs.spring.boot.webflux)
  implementation(libs.spring.boot.autoconfigure)
  implementation(libs.kotlinx.core)
  implementation(libs.kotlinx.reactive)
  implementation(libs.kotlinx.reactor)
  implementation(libs.kotlinx.jdk8)
  annotationProcessor(libs.spring.boot.annotationProcessor)
}

dependencies {
  testImplementation(libs.stove.testing)
  testImplementation(libs.stove.testing.couchbase)
  testImplementation(libs.stove.testing.http)
  testImplementation(libs.stove.testing.wiremock)
  testImplementation(libs.stove.testing.kafka)
  testImplementation(libs.stove.spring.testing)
  testImplementation(libs.ktor.client.websockets)
  testImplementation(libs.ktor.client.okhttp)
  testImplementation(libs.ktor.client.content.negotiation)
  testImplementation(libs.ktor.serialization.jackson.json)
}
