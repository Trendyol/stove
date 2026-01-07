dependencies {
  api(projects.lib.stoveRdbms)
  api(libs.testcontainers.mssql)
  api(libs.microsoft.sqlserver.jdbc)
}

dependencies {
  testImplementation(project(":test-extensions:stove-extensions-kotest"))
  testImplementation(libs.logback.classic)
}

val testWithProvided = tasks.register<Test>("testWithProvided") {
  group = "verification"
  description = "Runs tests with an externally provided MSSQL instance"
  testClassesDirs = sourceSets.test.get().output.classesDirs
  classpath = sourceSets.test.get().runtimeClasspath
  useJUnitPlatform()
  systemProperty("useProvided", "true")
  doFirst {
    println("Starting MSSQL tests with provided instance...")
  }
}

tasks.test.configure {
  dependsOn(testWithProvided)
}
