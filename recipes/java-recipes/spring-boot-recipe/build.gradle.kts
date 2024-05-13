plugins {
  alias(libs.plugins.spring.boot)
  alias(libs.plugins.spring.plugin)
  alias(libs.plugins.spring.dependencyManagement)
}

dependencies {
  implementation(libs.spring.boot.webflux)
  implementation(libs.spring.boot.autoconfigure)
  implementation(libs.spring.boot.kafka)
  implementation(libs.couchbase.client)
  implementation(libs.jackson.databind)
  implementation(libs.jackson.core)
  annotationProcessor(libs.spring.boot.annotationProcessor)
}

dependencies {
  testImplementation(libs.kotest.runner.junit5)
  testImplementation(libs.kotest.framework.api.jvm)
  testImplementation(libs.kotest.property.jvm)
  testImplementation(libs.stove.testing)
  testImplementation(libs.stove.testing.couchbase)
  testImplementation(libs.stove.testing.http)
  testImplementation(libs.stove.testing.wiremock)
  testImplementation(libs.stove.testing.kafka)
  testImplementation(libs.stove.spring.testing)
  testImplementation(libs.jackson.kotlin)
}
