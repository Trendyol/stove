dependencies {
  api(projects.lib.stove)
  api(libs.testcontainers.kafka)
  compileOnly(libs.spring.boot.kafka)
  implementation(libs.pprint)
}

dependencies {
  testImplementation(libs.kotest.runner.junit6)
  testImplementation(libs.kafka)
}
