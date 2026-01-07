dependencies {
  api(projects.starters.ktor.stoveKtor)
  implementation(libs.ktor.server.netty)
  implementation(libs.koin.ktor)
  testImplementation(testFixtures(projects.starters.ktor.tests.ktorTestFixtures))
}

dependencies {
  testImplementation(project(":test-extensions:stove-extensions-kotest"))
  testImplementation(libs.slf4j.simple)
}

tasks.test.configure {
  systemProperty("kotest.framework.config.fqn", "com.trendyol.stove.ktor.KoinStove")
}
