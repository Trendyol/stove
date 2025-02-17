@file:Suppress("UnstableApiUsage")

plugins {
  kotlin("jvm") version libs.versions.kotlin
  application
  idea
  kotlin("plugin.serialization") version libs.versions.kotlin
}

application {
  val groupId = rootProject.group.toString()
  val artifactId = project.name
  mainClass.set("$groupId.$artifactId.ApplicationKt")

  val isDevelopment: Boolean = project.ext.has("development")
  applicationDefaultJvmArgs = listOf("-Dio.ktor.development=$isDevelopment")
}

dependencies {
  implementation(libs.ktor.server)
  implementation(libs.ktor.server.netty)
  implementation(libs.ktor.serialization.kotlinx.json)
  implementation(libs.ktor.server.call.logging)
  implementation(libs.koin.ktor)
  implementation(libs.koin.logger.slf4j)
  implementation(libs.kotlinx.reactor)
  implementation(libs.kotlinx.core)
  implementation(libs.r2dbc.postgresql)
  implementation(libs.kafka)
  implementation(libs.hoplite.yaml)
  implementation(libs.jackson.kotlin)
  implementation(libs.jackson.databind)
}

dependencies {
  testImplementation(libs.kotest.property)
  testImplementation(libs.kotest.runner.junit5)
  testImplementation(libs.logback.classic)

  testImplementation(projects.stove.lib.stoveTestingE2eHttp)
  testImplementation(projects.stove.lib.stoveTestingE2eWiremock)
  testImplementation(projects.stove.lib.stoveTestingE2eRdbmsPostgres)
  testImplementation(projects.stove.lib.stoveTestingE2eKafka)
  testImplementation(projects.stove.starters.ktor.stoveKtorTestingE2e)
}

repositories {
  mavenCentral()
  maven { url = uri("https://oss.sonatype.org/content/repositories/snapshots") }
}
