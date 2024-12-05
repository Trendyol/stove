dependencies {
  api(projects.lib.stoveTestingE2e)
  api(libs.testcontainers.mongodb)
  implementation(libs.mongodb.kotlin.coroutine)
  implementation(libs.kotlinx.io.reactor.extensions)
  implementation(libs.kotlinx.reactive)
  implementation(libs.kotlinx.jdk8)
  implementation(libs.kotlinx.core)
}

tasks.test.configure {
  systemProperty("kotest.framework.config.fqn", "com.trendyol.stove.testing.e2e.mongodb.Stove")
}

dependencies {
  testImplementation(libs.logback.classic)
}
