plugins {}

dependencies {
  api(projects.lib.stoveTestingE2e)
  api(libs.testcontainers.elasticsearch)
  api(libs.ktor.client.core)
  api(libs.ktor.client.okhttp)
  api(libs.ktor.client.content.negotiation)
  api(libs.ktor.serialization.jackson.json)
  implementation(libs.jackson.databind)
}

dependencies {
  testImplementation(libs.slf4j.simple)
}

val testWithProvided = tasks.register<Test>("testWithProvided") {
  group = "verification"
  description = "Runs tests with an externally provided Elasticsearch instance"
  testClassesDirs = sourceSets.test.get().output.classesDirs
  classpath = sourceSets.test.get().runtimeClasspath
  useJUnitPlatform()
  systemProperty("useProvided", "true")
  doFirst {
    println("Starting Elasticsearch tests with provided instance...")
  }
}

tasks.test.configure {
  dependsOn(testWithProvided)
}
