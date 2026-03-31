dependencies {
  api(projects.lib.stove)
  api(projects.lib.stoveDashboardApi)
  implementation(libs.io.grpc.netty)
  implementation(libs.kotlinx.core)

  compileOnly(projects.lib.stoveTracing)

  testImplementation(libs.io.grpc.netty)
}
