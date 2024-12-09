@file:Suppress("UnstableApiUsage", "DSL_SCOPE_VIOLATION")

plugins {
  kotlin("jvm") version libs.versions.kotlin
  id("org.jetbrains.kotlin.plugin.allopen") version libs.versions.kotlin
  kotlin("plugin.serialization") version libs.versions.kotlin
  application
  idea
  id("com.google.devtools.ksp") version "1.9.25-1.0.20"
  id("com.github.johnrengelman.shadow") version "8.1.1"
  id("io.micronaut.application") version "4.4.3"
  id("io.micronaut.aot") version "4.4.3"
}

repositories {
  mavenCentral()
  maven { url = uri("https://oss.sonatype.org/content/repositories/snapshots") }
}

dependencies {
  runtimeOnly("org.yaml:snakeyaml:2.1")
  implementation(platform("io.micronaut.platform:micronaut-parent:4.7.1"))
  ksp("io.micronaut:micronaut-http-validation")
  ksp("io.micronaut.serde:micronaut-serde-processor")
  implementation("io.micronaut.kotlin:micronaut-kotlin-runtime")
  implementation("io.micronaut.serde:micronaut-serde-jackson")
  implementation("io.micronaut:micronaut-http-client")
  implementation("io.micronaut:micronaut-http-server-netty")
  implementation("io.micronaut:micronaut-inject")
  implementation("io.micronaut:micronaut-core")
  runtimeOnly("com.fasterxml.jackson.module:jackson-module-kotlin")
  implementation("com.couchbase.client:metrics-micrometer:0.7.5")
  implementation("io.micronaut.configuration:micronaut-micrometer-core:1.3.1")
  implementation("org.apache.kafka:kafka-clients")
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
  testImplementation(projects.stove.starters.micronautStarter.stoveMicronautTestingE2e)
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
