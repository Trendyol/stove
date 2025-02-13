plugins {
  kotlin("jvm") version libs.versions.kotlin
  id("org.jetbrains.kotlin.plugin.allopen") version libs.versions.kotlin
  kotlin("plugin.serialization") version libs.versions.kotlin
  alias(libs.plugins.google.ksp)
  alias(libs.plugins.shadowJar)
  alias(libs.plugins.micronaut.application)
  alias(libs.plugins.micronaut.aot)
  application
  idea
}

dependencies {
  runtimeOnly(libs.snakeyaml)
  implementation(platform(libs.micronaut.parent))
  implementation(libs.micronaut.kotlin.runtime)
  implementation(libs.micronaut.serde.jackson)
  implementation(libs.micronaut.http.client)
  implementation(libs.micronaut.http.server.netty)
  implementation(libs.micronaut.inject)
  implementation(libs.micronaut.core)
  implementation(libs.micronaut.micrometer.core)
  implementation(libs.jackson.kotlin)
  implementation(libs.couchbase.client.metrics)
  implementation(libs.kafka)
  implementation(libs.kotlinx.reactor)
  implementation(libs.kotlinx.core)
  implementation(libs.kotlinx.reactive)
  implementation(libs.couchbase.client)
  implementation(libs.couchbase.client.metrics)
  implementation(libs.jackson.kotlin)
  implementation(libs.kotlinx.slf4j)
}

dependencies {
  testImplementation(libs.kotest.property)
  testImplementation(libs.kotest.runner.junit5)
  testImplementation(projects.stove.lib.stoveTestingE2eHttp)
  testImplementation(projects.stove.lib.stoveTestingE2eWiremock)
  testImplementation(projects.stove.lib.stoveTestingE2eCouchbase)
  testImplementation(projects.stove.lib.stoveTestingE2eElasticsearch)
  testImplementation(projects.stove.starters.micronaut.stoveMicronautTestingE2e)
}

application {
  mainClass = "stove.micronaut.example.ApplicationKt"
}

graalvmNative.toolchainDetection = false

java {
  sourceCompatibility = JavaVersion.toVersion("17")
}

micronaut {
  runtime("netty")
  testRuntime("kotest5")
  processing {
    incremental(true)
    annotations("stove.micronaut.example.*")
  }
  aot {
    optimizeServiceLoading = false
    convertYamlToJava = false
    precomputeOperations = true
    cacheEnvironment = true
    optimizeClassLoading = true
    deduceEnvironment = true
    optimizeNetty = true
    replaceLogbackXml = true
  }
}
