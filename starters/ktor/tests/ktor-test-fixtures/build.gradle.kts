plugins {
  `java-test-fixtures`
}

dependencies {
  testFixturesApi(projects.starters.ktor.stoveKtor)
  testFixturesApi(libs.kotest.runner.junit5)
  testFixturesApi(libs.ktor.server.host.common)

  // DI systems as compileOnly - version provided by consuming module
  testFixturesCompileOnly(libs.koin.ktor)
  testFixturesCompileOnly(libs.ktor.server.di)
}
