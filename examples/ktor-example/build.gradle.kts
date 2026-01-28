@file:Suppress("UnstableApiUsage")

import com.trendyol.stove.gradle.configureStoveTracing

plugins {
  kotlin("jvm") version libs.versions.kotlin
  application
  idea
  kotlin("plugin.serialization") version libs.versions.kotlin
  alias(libs.plugins.protobuf)
}

application {
  val groupId = rootProject.group.toString()
  val artifactId = project.name
  mainClass.set("$groupId.$artifactId.ApplicationKt")

  val isDevelopment: Boolean = project.ext.has("development")
  applicationDefaultJvmArgs = listOf("-Dio.ktor.development=$isDevelopment")
}

configureStoveTracing {
  serviceName = "ktor-example"
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

  // OpenTelemetry API for manual span recording (exceptions in catch blocks)
  implementation(libs.opentelemetry.api)

  // gRPC service clients (FeatureToggle, Pricing)
  implementation(libs.io.grpc)
  implementation(libs.io.grpc.stub)
  implementation(libs.io.grpc.protobuf)
  implementation(libs.io.grpc.netty)
  implementation(libs.io.grpc.kotlin)
  implementation(libs.google.protobuf.kotlin)

  testImplementation(project(":test-extensions:stove-extensions-kotest"))
  testImplementation(libs.logback.classic)
  testImplementation(projects.stove.lib.stoveHttp)
  testImplementation(projects.stove.lib.stoveWiremock)
  testImplementation(projects.stove.lib.stovePostgres)
  testImplementation(projects.stove.lib.stoveKafka)
  testImplementation(projects.stove.lib.stoveGrpc)
  testImplementation(projects.stove.lib.stoveGrpcMock)
  testImplementation(projects.stove.starters.ktor.stoveKtor)
}

repositories {
  mavenCentral()
  maven { url = uri("https://oss.sonatype.org/content/repositories/snapshots") }
}

protobuf {
  protoc {
    artifact = libs.protoc.get().toString()
  }

  plugins {
    create("grpc").apply {
      artifact = libs.grpc.protoc.gen.java.get().toString()
    }
    create("grpckt").apply {
      artifact = "${libs.grpc.protoc.gen.kotlin.get()}:jdk8@jar"
    }
  }

  generateProtoTasks {
    all().forEach { task ->
      task.plugins {
        create("grpc")
        create("grpckt")
      }
      task.builtins {
        create("kotlin")
      }
    }
  }
}
