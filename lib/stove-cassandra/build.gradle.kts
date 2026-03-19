dependencies {
  api(projects.lib.stove)
  api(libs.cassandra.driver.core)
  api(libs.testcontainers.cassandra)
}

dependencies {
  testImplementation(project(":test-extensions:stove-extensions-kotest"))
  testImplementation(libs.logback.classic)
}

val testWithProvided = tasks.register<Test>("testWithProvided") {
  group = "verification"
  description = "Runs tests with an externally provided Cassandra instance"
  testClassesDirs = sourceSets.test.get().output.classesDirs
  classpath = sourceSets.test.get().runtimeClasspath
  useJUnitPlatform()
  systemProperty("useProvided", "true")
  doFirst {
    println("Starting Cassandra tests with provided instance...")
  }
}

tasks.test.configure {
  dependsOn(testWithProvided)
}
