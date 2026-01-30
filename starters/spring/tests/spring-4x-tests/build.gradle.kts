dependencies {
  api(projects.starters.spring.stoveSpring)
  implementation(libs.spring.boot.four)

  testImplementation(projects.testExtensions.stoveExtensionsKotest)
  testImplementation(testFixtures(projects.starters.spring.tests.springTestFixtures))
  testImplementation(libs.spring.boot.four.autoconfigure)
  testImplementation(libs.slf4j.simple)
}

tasks.test.configure {
  systemProperty("kotest.framework.config.fqn", "com.trendyol.stove.Stove")
}
