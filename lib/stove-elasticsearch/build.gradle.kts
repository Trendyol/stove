plugins {}

dependencies {
  api(projects.lib.stove)
  api(projects.lib.stoveTracing)
  api(libs.elastic)
  api(libs.testcontainers.elasticsearch)
  implementation(libs.jackson.databind)
}

dependencies {
  testImplementation(project(":test-extensions:stove-extensions-kotest"))
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
