import com.trendyol.stove.gradle.stoveTracing

plugins {
  alias(libs.plugins.spring.plugin)
  alias(libs.plugins.spring.boot.four)
  idea
  application
}

dependencies {
  implementation(libs.spring.boot.four)
  implementation(libs.spring.boot.four.autoconfigure)
  implementation(libs.spring.boot.four.webflux)
  implementation(libs.spring.boot.four.actuator)
  annotationProcessor(libs.spring.boot.four.annotationProcessor)
  implementation(libs.spring.boot.four.kafka)
  implementation(libs.kotlinx.reactor)
  implementation(libs.kotlinx.core)
  implementation(libs.kotlinx.reactive)
  implementation(libs.kotlinx.slf4j)

  // OpenTelemetry instrumentation API for @WithSpan annotation
  implementation(libs.opentelemetry.instrumentation.annotations)
}

dependencies {
  testImplementation(projects.stove.testExtensions.stoveExtensionsKotest)
  testImplementation(libs.jackson3.kotlin)
  testImplementation(projects.stove.lib.stoveHttp)
  testImplementation(projects.stove.lib.stoveWiremock)
  testImplementation(projects.stove.lib.stoveTracing)
  testImplementation(projects.stove.starters.spring.stoveSpring)
  testImplementation(projects.stove.starters.spring.stoveSpringKafka)
}

application { mainClass.set("stove.spring.example4x.ExampleAppkt") }

// ============================================================================
// TRACING SETUP - OpenTelemetry Java Agent (buildSrc)
// ============================================================================
stoveTracing {
  serviceName = "spring-4x-example"
}

tasks.test {
  testLogging {
    events("passed", "skipped", "failed", "standardOut", "standardError")
    showStandardStreams = true
  }
}
