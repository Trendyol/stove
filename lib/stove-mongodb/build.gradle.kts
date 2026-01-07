dependencies {
  api(projects.lib.stove)
  api(libs.testcontainers.mongodb)
  implementation(libs.mongodb.kotlin.coroutine)
  implementation(libs.kotlinx.io.reactor.extensions)
  implementation(libs.kotlinx.reactive)
  implementation(libs.kotlinx.jdk8)
  implementation(libs.kotlinx.core)
}

dependencies {
  testImplementation(project(":test-extensions:stove-extensions-kotest"))
  testImplementation(libs.logback.classic)
}

val testWithProvided = tasks.register<Test>("testWithProvided") {
  group = "verification"
  description = "Runs tests with an externally provided MongoDB instance"
  testClassesDirs = sourceSets.test.get().output.classesDirs
  classpath = sourceSets.test.get().runtimeClasspath
  useJUnitPlatform()
  systemProperty("useProvided", "true")
  doFirst {
    println("Starting MongoDB tests with provided instance...")
  }
}

tasks.test.configure {
  dependsOn(testWithProvided)
}
