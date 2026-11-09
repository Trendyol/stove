dependencies {
  api(projects.lib.stove)
  compileOnly(enforcedPlatform(libs.quarkus))
  compileOnly(libs.quarkus.core)
  compileOnly(libs.quarkus.arc)
  testImplementation(libs.quarkus.arc)
  testImplementation(enforcedPlatform(libs.quarkus))
}
