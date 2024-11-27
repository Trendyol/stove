dependencies {
  api(projects.lib.stoveTestingE2e)
  api(libs.testcontainers.mongodb)
  api(libs.mongojack) {
    exclude(group = "org.mongodb", module = "mongodb-driver-sync")
  }
  implementation(libs.mongodb.kotlin.coroutine)
  implementation(libs.kotlinx.io.reactor.extensions)
  implementation(libs.kotlinx.reactive)
  implementation(libs.kotlinx.jdk8)
  implementation(libs.kotlinx.core)
}

dependencies {
  testImplementation(libs.slf4j.simple)
}
