dependencies {
  api(projects.lib.stove)
  api(libs.io.grpc)
  api(libs.io.grpc.stub)
  api(libs.io.grpc.protobuf)
  api(libs.google.protobuf.util)
  api(libs.caffeine)
  implementation(libs.kotlinx.core)
}

dependencies {
  testImplementation(project(":test-extensions:stove-extensions-kotest"))
  testImplementation(libs.logback.classic)
  testImplementation(libs.kotest.runner.junit5)
  testImplementation(testFixtures(projects.lib.stove))
  testImplementation(projects.lib.stoveGrpc)
  testImplementation(libs.io.grpc.netty)
  testImplementation(libs.io.grpc.kotlin)
  testImplementation(libs.google.protobuf.kotlin)
}

plugins {
  alias(libs.plugins.protobuf)
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
      // Generate descriptor set for tests
      task.generateDescriptorSet = true
      task.descriptorSetOptions.includeImports = true
    }
  }
}

tasks.named("compileTestKotlin") {
  dependsOn("generateTestProto")
}
