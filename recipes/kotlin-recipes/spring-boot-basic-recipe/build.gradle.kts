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
  testImplementation(stoveLibs.stove)
  testImplementation(stoveLibs.stoveCouchbase)
  testImplementation(stoveLibs.stoveHttp)
  testImplementation(stoveLibs.stoveWiremock)
  testImplementation(stoveLibs.stoveKafka)
  testImplementation(stoveLibs.stoveSpring)
  testImplementation(libs.ktor.client.websockets)
  testImplementation(libs.ktor.client.okhttp)
  testImplementation(libs.ktor.client.content.negotiation)
  testImplementation(libs.ktor.serialization.jackson.json)
}
