dependencies {
  api(projects.lib.stove)
  api(libs.testcontainers.kafka)
  api(projects.lib.stoveKafkaCommon)
  compileOnly(libs.spring.boot.kafka)
  implementation(libs.pprint)
}

dependencies {
  testImplementation(libs.kotest.runner.junit6)
}
