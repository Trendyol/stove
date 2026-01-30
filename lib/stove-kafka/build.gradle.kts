plugins {
  alias(libs.plugins.wire)
}

dependencies {
  api(projects.lib.stove)
  api(libs.testcontainers.kafka)
  api(libs.kafka)
  api(libs.kafka.embedded)
  implementation(libs.kotlinx.io.reactor.extensions)
  implementation(libs.kotlinx.jdk8)
  implementation(libs.kotlinx.core)
  implementation(libs.wire.grpc.server)
  implementation(libs.wire.grpc.client)
  implementation(libs.wire.grpc.runtime)
  implementation(libs.io.grpc)
  implementation(libs.io.grpc.protobuf)
  implementation(libs.io.grpc.stub)
  implementation(libs.io.grpc.kotlin)
  implementation(libs.io.grpc.netty)
  implementation(libs.google.protobuf.kotlin)
  implementation(libs.caffeine)
  implementation(libs.pprint)
}

dependencies {
  testImplementation(project(":test-extensions:stove-extensions-kotest"))
  testImplementation(libs.logback.classic)
  testImplementation(libs.kafkaKotlin)
}

buildscript {
  dependencies {
    classpath(libs.wire.grpc.server.generator)
  }
}

wire {
  sourcePath("src/main/proto")
  kotlin {
    rpcRole = "client"
    rpcCallStyle = "suspending"
    exclusive = false
    javaInterop = false
  }
  kotlin {
    custom {
      schemaHandlerFactory = com.squareup.wire.kotlin.grpcserver.GrpcServerSchemaHandler.Factory()
      options = mapOf(
        "singleMethodServices" to "false",
        "rpcCallStyle" to "suspending"
      )
    }
    rpcRole = "server"
    rpcCallStyle = "suspending"
    exclusive = false
    singleMethodServices = false
    javaInterop = true
    includes = listOf("com.trendyol.stove.kafka.StoveKafkaObserverService")
  }
}

val testWithEmbedded = tasks.register<Test>("testWithEmbedded") {
  group = "verification"
  description = "Runs tests with embedded Kafka"
  testClassesDirs = sourceSets.test.get().output.classesDirs
  classpath = sourceSets.test.get().runtimeClasspath
  useJUnitPlatform()
  systemProperty("useEmbeddedKafka", "true")
  doFirst {
    println("Starting embedded Kafka tests...")
  }
}

val testWithProvided = tasks.register<Test>("testWithProvided") {
  group = "verification"
  description = "Runs tests with an externally provided Kafka instance"
  testClassesDirs = sourceSets.test.get().output.classesDirs
  classpath = sourceSets.test.get().runtimeClasspath
  useJUnitPlatform()
  systemProperty("useProvided", "true")
  doFirst {
    println("Starting Kafka tests with provided instance...")
  }
}

tasks.test.configure {
  dependsOn(testWithEmbedded, testWithProvided)
}
