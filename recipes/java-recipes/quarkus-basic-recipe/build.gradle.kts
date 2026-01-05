plugins {
  alias(libs.plugins.quarkus)
  id("org.jetbrains.kotlin.plugin.allopen") version libs.versions.kotlin
  java
}

allOpen {
  annotation("jakarta.ws.rs.Path")
  annotation("jakarta.enterprise.context.ApplicationScoped")
  annotation("jakarta.persistence.Entity")
  annotation("io.quarkus.test.junit.QuarkusTest")
}

kotlin {
  compilerOptions {
    jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21
    javaParameters = true
  }
}

tasks.e2eTest {
  enabled = runningLocally
}

dependencies {
  implementation(enforcedPlatform(libs.quarkus))
  implementation(libs.quarkus.rest)
  implementation(libs.quarkus.arc)
  implementation(libs.quarkus.kotlin)
  implementation(libs.logback.classic)
  implementation(libs.slf4j.api)
  implementation(libs.kotlinx.reactor)
  implementation(libs.kotlinx.core)
  // Removed shared.application to avoid IDE launcher classpath issues
  // implementation(projects.shared.application)
}

dependencies {
  testImplementation(libs.stove.testing)
  testImplementation(libs.stove.testing.couchbase)
  testImplementation(libs.stove.testing.http)
  testImplementation(libs.stove.testing.wiremock)
  testImplementation(libs.stove.testing.kafka)
}
