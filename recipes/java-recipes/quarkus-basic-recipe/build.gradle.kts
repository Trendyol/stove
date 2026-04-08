plugins {
  alias(libs.plugins.quarkus)
  id("com.trendyol.stove.tracing") version libs.versions.stove.get()
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
}

dependencies {
  testImplementation(stoveLibs.stove)
  testImplementation(stoveLibs.stoveQuarkus)
  testImplementation(stoveLibs.stoveCouchbase)
  testImplementation(stoveLibs.stoveExtensionsKotest)
  testImplementation(stoveLibs.stoveHttp)
  testImplementation(stoveLibs.stoveTracing)
  testImplementation(stoveLibs.stoveWiremock)
  testImplementation(stoveLibs.stoveKafka)
}

stoveTracing {
  serviceName.set("quarkus-basic-recipe")
  testTaskNames.set(listOf("e2eTest"))
  otelAgentVersion.set(libs.opentelemetry.instrumentation.annotations.get().version!!)
}
