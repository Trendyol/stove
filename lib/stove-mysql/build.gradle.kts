dependencies {
  api(projects.lib.stoveRdbms)
  api(libs.testcontainers.mysql)
  api(libs.mysql.connector)
  testImplementation(projects.testExtensions.stoveExtensionsKotest)
  testImplementation(libs.logback.classic)
}

val testWithProvided = tasks.register<Test>("testWithProvided") {
  group = "verification"
  description = "Runs tests with an externally provided MySQL instance"
  testClassesDirs = sourceSets.test.get().output.classesDirs
  classpath = sourceSets.test.get().runtimeClasspath
  useJUnitPlatform()
  systemProperty("useProvided", "true")
  doFirst {
    println("Starting MySQL tests with provided instance...")
  }
}

tasks.test.configure {
  dependsOn(testWithProvided)
}
