@file:Suppress("UnstableApiUsage")

plugins {
  `kotlin-dsl`
  `java-gradle-plugin`
  alias(libs.plugins.maven.publish)
  alias(libs.plugins.plugin.publish)
}

gradlePlugin {
  website.set("https://github.com/Trendyol/stove")
  vcsUrl.set("https://github.com/Trendyol/stove")

  plugins {
    create("stoveTracing") {
      id = "com.trendyol.stove.tracing"
      implementationClass = "com.trendyol.stove.gradle.StoveTracingPlugin"
      displayName = "Stove Tracing Plugin"
      description = "Configures OpenTelemetry Java Agent for Stove end-to-end tests. Enables test failure reports with full execution traces, call chains, and span correlation across HTTP, Kafka, gRPC, and database calls."
      tags.set(
        listOf(
          "testing",
          "e2e",
          "integration-testing",
          "opentelemetry",
          "tracing",
          "kotlin",
          "telemetry",
          "debugging",
          "stove",
        ),
      )
    }
  }
}

tasks.test {
  useJUnitPlatform()
}

dependencies {
  testImplementation(libs.kotest.runner.junit5)
  testImplementation(libs.kotest.framework.engine)
  testImplementation(libs.kotest.assertions.core)
}
