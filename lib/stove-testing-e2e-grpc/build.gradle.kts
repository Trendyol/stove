plugins {
  alias(libs.plugins.wire)
}

dependencies {
  api(projects.lib.stoveTestingE2e)
  api(libs.io.grpc)
  api(libs.io.grpc.stub)
  api(libs.io.grpc.kotlin)
  api(libs.io.grpc.netty)
  api(libs.io.grpc.protobuf)
  api(libs.wire.grpc.client)
  api(libs.wire.grpc.runtime)
  api(libs.google.protobuf.kotlin)
  implementation(libs.wire.grpc.server)
  implementation(libs.kotlin.reflect)
  implementation(libs.kotlinx.core)
  implementation(libs.kotlinx.jdk8)
}

dependencies {
  testImplementation(libs.logback.classic)
  testImplementation(libs.kotest.runner.junit5)
  testImplementation(testFixtures(projects.lib.stoveTestingE2e))
}

buildscript {
  dependencies {
    classpath(libs.wire.grpc.server.generator)
  }
}

wire {
  sourcePath("src/test/proto")

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
    includes = listOf("com.trendyol.stove.testing.e2e.grpc.test.TestService")
  }
}
