plugins {
  kotlin("jvm") version libs.versions.kotlin
  kotlin("plugin.serialization") version libs.versions.kotlin
  alias(libs.plugins.google.ksp)
  alias(libs.plugins.micronaut.application)
  alias(libs.plugins.micronaut.aot)
  application
  idea
}

dependencies {
  runtimeOnly(libs.snakeyaml)
  ksp(platform(libs.micronaut.platform))
  ksp(libs.micronaut.inject.kotlin)
  implementation(libs.micronaut.kotlin.runtime)
  implementation(libs.micronaut.serde.jackson)
  implementation(libs.micronaut.http.client)
  implementation(libs.micronaut.http.server.netty)
  implementation(libs.micronaut.inject)
  implementation(libs.micronaut.core)
  implementation(libs.micronaut.micrometer.core)
  implementation(libs.micronaut.data.r2dbc)
  implementation(libs.jackson.kotlin)
  implementation(libs.kafka)
  implementation(libs.kotlinx.reactor)
  implementation(libs.kotlinx.core)
  implementation(libs.kotlinx.reactive)
  implementation(libs.r2dbc.postgresql)
  implementation(libs.postgresql)
  implementation(libs.jackson.kotlin)
  implementation(libs.kotlinx.slf4j)
}

dependencies {
  testImplementation(projects.stove.lib.stoveHttp)
  testImplementation(projects.stove.lib.stoveWiremock)
  testImplementation(projects.stove.lib.stovePostgres)
  testImplementation(projects.stove.lib.stoveElasticsearch)
  testImplementation(projects.stove.starters.micronaut.stoveMicronaut)
  testImplementation(projects.testExtensions.stoveExtensionsKotest)
}

application {
  mainClass = "stove.micronaut.example.ApplicationKt"
}

graalvmNative.toolchainDetection = false

java {
  sourceCompatibility = JavaVersion.toVersion("17")
}

micronaut {
  version(libs.versions.micronaut.platform.get())
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
