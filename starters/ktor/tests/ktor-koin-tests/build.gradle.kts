dependencies {
  api(projects.starters.ktor.stoveKtorTestingE2e)
  implementation(libs.ktor.server.netty)
  implementation(libs.koin.ktor)
  testImplementation(testFixtures(projects.starters.ktor.tests.ktorTestFixtures))
}

dependencies {
  testImplementation(libs.slf4j.simple)
}

tasks.test.configure {
  systemProperty("kotest.framework.config.fqn", "com.trendyol.stove.testing.e2e.ktor.KoinStove")
}

