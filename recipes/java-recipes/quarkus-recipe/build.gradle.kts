plugins {
  alias(libs.plugins.quarkus)
}

dependencies {
  implementation(platform(libs.quarkus))
  implementation(libs.quarkus.rest)
  implementation(libs.quarkus.arc)
  implementation(libs.couchbase.client)
  implementation(libs.jackson.databind)
  implementation(libs.jackson.core)
  implementation(projects.shared.application)
}

dependencies {
  testImplementation(libs.kotest.runner.junit5)
  testImplementation(libs.kotest.framework.api)
  testImplementation(libs.kotest.property)
  testImplementation(libs.stove.testing)
  testImplementation(libs.stove.testing.couchbase)
  testImplementation(libs.stove.testing.http)
  testImplementation(libs.stove.testing.wiremock)
  testImplementation(libs.stove.testing.kafka)
  testImplementation(libs.stove.spring.testing)
  testImplementation(libs.jackson.kotlin)
}

tasks.e2eTest.configure {
  systemProperty("kotest.framework.config.fqn", "com.trendyol.stove.recipes.quarkus.e2e.setup.Stove")
}

