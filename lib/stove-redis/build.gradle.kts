dependencies {
  api(projects.lib.stove)
  api(libs.lettuce.core)
  api(libs.testcontainers.redis)
}

dependencies {
  testImplementation(project(":test-extensions:stove-extensions-kotest"))
}

val testWithProvided = tasks.register<Test>("testWithProvided") {
  group = "verification"
  description = "Runs tests with an externally provided Redis instance"
  testClassesDirs = sourceSets.test.get().output.classesDirs
  classpath = sourceSets.test.get().runtimeClasspath
  useJUnitPlatform()
  systemProperty("useProvided", "true")
  doFirst {
    println("Starting Redis tests with provided instance...")
  }
}

tasks.test.configure {
  dependsOn(testWithProvided)
}
