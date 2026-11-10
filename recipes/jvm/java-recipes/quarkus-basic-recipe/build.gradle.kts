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

// This recipe is an e2e fixture; preserve CDI beans and share augmented application classes.
quarkus {
  quarkusBuildProperties.put("quarkus.profile", "e2e")
}

val augmentedJar = tasks.named<io.quarkus.gradle.tasks.QuarkusBuild>("quarkusBuild").map { it.runnerJar }

tasks.e2eTest {
  dependsOn("quarkusBuild")
  classpath = files(augmentedJar) +
    (sourceSets["test-e2e"].runtimeClasspath - sourceSets.main.get().output)
  systemProperty("quarkus.profile", "e2e")
  systemProperty("java.util.logging.manager", "org.jboss.logmanager.LogManager")
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
