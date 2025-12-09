dependencies {
  api(projects.starters.spring.stoveSpringTestingE2eKafka)
  implementation(libs.spring.boot.kafka)
}

dependencies {
  testImplementation(testFixtures(projects.starters.spring.tests.springTestFixtures))
  testImplementation(libs.spring.boot.autoconfigure)
  testImplementation(projects.starters.spring.tests.spring2xTests)
  testImplementation(libs.logback.classic)
}

tasks.test.configure {
  systemProperty("kotest.framework.config.fqn", "com.trendyol.stove.testing.e2e.kafka.Setup")
}
