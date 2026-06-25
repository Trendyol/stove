dependencies {
  api(projects.lib.stove)

  testImplementation(libs.kotest.runner.junit6)
  testImplementation(libs.kotest.framework.engine)
  testImplementation(libs.kotest.assertions.core)
}
