plugins {
  alias(libs.plugins.wire)
}

dependencies {
  api(projects.lib.stoveTestingE2e)
  api(libs.testcontainers.kafka)
  implementation(libs.kafka)
  implementation(libs.kotlinx.io.reactor.extensions)
  implementation(libs.kotlinx.jdk8)
  implementation(libs.kotlinx.core)
  implementation(libs.kafkaKotlin)

  api(libs.wire.grpc.server)
  api(libs.wire.grpc.client)
  api(libs.wire.grpc.runtime)
  api(libs.io.grpc)
  api(libs.io.grpc.protobuf)
  api(libs.io.grpc.stub)
  api(libs.io.grpc.kotlin)
  api(libs.io.grpc.netty)
  api(libs.google.protobuf.kotlin)
}

dependencies {
  testImplementation(libs.slf4j.simple)
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
    javaInterop = true
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
    includes = listOf("StoveKafkaObserverService")
  }
}
