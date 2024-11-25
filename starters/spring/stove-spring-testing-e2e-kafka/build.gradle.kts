dependencies {
  api(projects.lib.stoveTestingE2e)
  api(libs.testcontainers.kafka)
  implementation(libs.spring.boot.kafka)
  implementation(libs.caffeine)
  implementation(libs.pprint)
}

dependencies {
  testAnnotationProcessor(libs.spring.boot.annotationProcessor)
  testImplementation(libs.spring.boot.autoconfigure)
  testImplementation(projects.starters.spring.stoveSpringTestingE2e)
  testImplementation(libs.logback.classic)
}
