dependencies {
  api(projects.lib.stove)
  api(projects.lib.stovePortalApi)
  implementation(libs.io.grpc.netty)
  implementation(libs.kotlinx.core)

  compileOnly(projects.lib.stoveTracing)

  testImplementation(libs.io.grpc.netty)
}
