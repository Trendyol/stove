dependencies {
  api(projects.starters.spring.stoveSpringTestingE2eKafka)
  implementation(libs.spring.boot.three.kafka)
}

dependencies {
  testImplementation(testFixtures(projects.starters.spring.tests.springTestFixtures))
  testImplementation(libs.spring.boot.three.autoconfigure)
  testImplementation(projects.starters.spring.tests.spring3xTests)
  testImplementation(libs.logback.classic)
}

tasks.test.configure {
  systemProperty("kotest.framework.config.fqn", "com.trendyol.stove.testing.e2e.kafka.Setup")
}
