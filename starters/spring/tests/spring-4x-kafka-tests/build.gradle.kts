dependencies {
  api(projects.starters.spring.stoveSpringKafka)
  implementation(libs.spring.boot.four.kafka)
}

dependencies {
  testImplementation(testFixtures(projects.starters.spring.tests.springTestFixtures))
  testImplementation(libs.spring.boot.four.autoconfigure)
  testImplementation(projects.starters.spring.tests.spring4xTests)
  testImplementation(libs.logback.classic)
}

tasks.test.configure {
  systemProperty("kotest.framework.config.fqn", "com.trendyol.stove.kafka.Setup")
}
