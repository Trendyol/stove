plugins {
  alias(libs.plugins.protobuf)
}

dependencies {
  api(libs.io.grpc)
  api(libs.io.grpc.stub)
  api(libs.io.grpc.protobuf)
  api(libs.io.grpc.kotlin)
  api(libs.google.protobuf.kotlin)
  api(libs.kotlinx.core)
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
