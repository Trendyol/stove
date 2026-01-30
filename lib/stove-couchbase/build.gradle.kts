dependencies {
  api(projects.lib.stove)
  api(libs.couchbase.kotlin)
  api(libs.testcontainers.couchbase)
}

dependencies {
  testImplementation(project(":test-extensions:stove-extensions-kotest"))
  testImplementation(libs.slf4j.simple)
}

val testWithProvided = tasks.register<Test>("testWithProvided") {
  group = "verification"
  description = "Runs tests with an externally provided Couchbase instance"
  testClassesDirs = sourceSets.test.get().output.classesDirs
  classpath = sourceSets.test.get().runtimeClasspath
  useJUnitPlatform()
  systemProperty("useProvided", "true")
  doFirst {
    println("Starting Couchbase tests with provided instance...")
  }
}

tasks.test.configure {
  dependsOn(testWithProvided)
}
