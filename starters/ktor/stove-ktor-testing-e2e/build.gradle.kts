dependencies {
  api(projects.lib.stoveTestingE2e)
  implementation(libs.ktor.server.host.common)
  
  // Both DI systems as compileOnly - users bring their preferred DI at runtime
  compileOnly(libs.koin.ktor)
  compileOnly(libs.ktor.server.di)
}
