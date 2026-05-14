dependencies {
  api(projects.lib.stove)
  implementation(libs.kotlinx.core)
  implementation(libs.slf4j.api)
  implementation(libs.opentelemetry.api)
  compileOnly(libs.logback.classic)
  compileOnly(libs.log4j.api)
  compileOnly(libs.log4j.core)

  testImplementation(libs.logback.classic)
  testImplementation(libs.log4j.api)
  testImplementation(libs.log4j.core)
}
