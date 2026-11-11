dependencies {
  testImplementation(testFixtures(projects.testExtensions.stoveExtensionsCommon))
  api(projects.testExtensions.stoveExtensionsCommon)
  api(libs.cucumber.java)
  api(libs.cucumber.plugin)
  implementation(libs.kotlin.reflect)
  testImplementation(libs.junit.platform.testkit)
  testImplementation(projects.testExtensions.stoveExtensionsJunit)
  testImplementation(projects.testExtensions.stoveExtensionsKotest)
  testRuntimeOnly(libs.junit.jupiter.engine)
  testImplementation(libs.cucumber.engine)
  testImplementation(libs.logback.classic)
  testRuntimeOnly(libs.jackson.jdk8)
}

tasks.test {
  exclude("**/Mixed*Fixture*")
  exclude("**/CucumberWithoutStoveTest*")
  dependsOn("testWithoutStove")
  useJUnitPlatform { includeEngines("kotest") }
}

// Stove's global instance cannot be uninitialized after startup; verify this contract in a fresh JVM.
tasks.register<Test>("testWithoutStove") {
  testClassesDirs = sourceSets.test.get().output.classesDirs
  classpath = sourceSets.test.get().runtimeClasspath
  include("**/CucumberWithoutStoveTest*")
  useJUnitPlatform { includeEngines("kotest") }
}
