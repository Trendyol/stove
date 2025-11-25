dependencies {
  api(projects.lib.stoveTestingE2eRdbms)
  api(libs.testcontainers.postgres)
  api(libs.postgresql)
  testImplementation(libs.logback.classic)
}

val testWithProvided = tasks.register<Test>("testWithProvided") {
  group = "verification"
  description = "Runs tests with an externally provided PostgreSQL instance"
  testClassesDirs = sourceSets.test.get().output.classesDirs
  classpath = sourceSets.test.get().runtimeClasspath
  useJUnitPlatform()
  systemProperty("useProvided", "true")
  doFirst {
    println("Starting PostgreSQL tests with provided instance...")
  }
}

tasks.test.configure {
  dependsOn(testWithProvided)
}

