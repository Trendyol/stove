dependencies {
  api(projects.lib.stove)
  implementation(libs.kotlinx.core)
  implementation(libs.kotlinx.slf4j)

  // OTLP gRPC protocol support for receiving spans from OTel Java Agent
  implementation(libs.opentelemetry.proto)
  implementation(libs.io.grpc)
  implementation(libs.io.grpc.stub)
  implementation(libs.io.grpc.protobuf)
  implementation(libs.io.grpc.netty)
}

dependencies {
  testImplementation(project(":test-extensions:stove-extensions-kotest"))
  testImplementation(libs.logback.classic)
}
