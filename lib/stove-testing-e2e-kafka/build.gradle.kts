plugins {
  alias(libs.plugins.wire)
}

dependencies {
  api(projects.lib.stoveTestingE2e)
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
    includes = listOf("com.trendyol.stove.testing.e2e.standalone.kafka.StoveKafkaObserverService")
  }
}
