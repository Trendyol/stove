plugins {
  kotlin("jvm") version "2.3.20"
  idea
}

// -- Go build ----------------------------------------------------------------
val goBinary = layout.buildDirectory.file("go-app").get().asFile

tasks.register<Exec>("buildGoApp") {
  description = "Compiles the Go application."
  group = "build"
  commandLine("go", "build", "-o", goBinary.absolutePath, ".")
  inputs.files(fileTree(".") { include("*.go", "go.mod", "go.sum") })
  outputs.file(goBinary)
}

// -- Test source set ----------------------------------------------------------
val stoveTests = "stovetests"

sourceSets {
  create(stoveTests) {
    kotlin {
      compileClasspath += sourceSets.main.get().output
      runtimeClasspath += sourceSets.main.get().output
      srcDirs("stovetests/kotlin")
    }
    resources.srcDirs("stovetests/resources")
  }
}

val stovetestsImplementation by configurations.getting {
  extendsFrom(configurations.testImplementation.get())
}
configurations["stovetestsRuntimeOnly"].extendsFrom(configurations.runtimeOnly.get())

idea {
  module {
    testSources.from(sourceSets[stoveTests].allSource.sourceDirectories)
    testResources.from(sourceSets[stoveTests].resources.sourceDirectories)
  }
}

// -- E2E test tasks -----------------------------------------------------------
val kafkaLibraries = listOf("sarama", "franz", "segmentio")

val kafkaE2eTasks = kafkaLibraries.mapIndexed { index, lib ->
  tasks.register<Test>("e2eTest_$lib") {
    description = "Runs e2e tests with the $lib Kafka library."
    group = "verification"
    dependsOn("buildGoApp")
    testClassesDirs = sourceSets[stoveTests].output.classesDirs
    classpath = sourceSets[stoveTests].runtimeClasspath
    useJUnitPlatform()
    systemProperty("go.app.binary", goBinary.absolutePath)
    systemProperty("kafka.library", lib)
    if (index > 0) mustRunAfter("e2eTest_${kafkaLibraries[index - 1]}")
  }
}

tasks.register<Test>("e2eTest") {
  description = "Runs e2e tests for all Kafka libraries."
  group = "verification"
  dependsOn(kafkaE2eTasks)
  enabled = false
}

// -- Dependencies -------------------------------------------------------------
dependencies {
  testImplementation(stoveLibs.stove)
  testImplementation(stoveLibs.stoveProcess)
  testImplementation(stoveLibs.stovePostgres)
  testImplementation(stoveLibs.stoveHttp)
  testImplementation(stoveLibs.stoveTracing)
  testImplementation(stoveLibs.stoveDashboard)
  testImplementation(stoveLibs.stoveKafka)
  testImplementation(stoveLibs.stoveExtensionsKotest)

  testImplementation(libs.kotest.runner.junit5)
  testImplementation(libs.kotest.framework.engine)
  testImplementation(libs.kotest.assertions.core)
}

// -- Kotlin / Java settings ---------------------------------------------------
kotlin { jvmToolchain(21) }

tasks.withType<Test> {
  useJUnitPlatform()
  jvmArgs("--add-opens", "java.base/java.util=ALL-UNNAMED")
}
