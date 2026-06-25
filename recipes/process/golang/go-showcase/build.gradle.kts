plugins {
  kotlin("jvm") version "2.4.0"
  idea
}

// -- Go build ----------------------------------------------------------------
val goBinary = layout.buildDirectory.file("go-app").get().asFile
val goExecutable = providers.environmentVariable("GO_EXECUTABLE").getOrElse("go")
val dockerExecutable = providers.environmentVariable("DOCKER_EXECUTABLE").getOrElse("docker")
val coverageEnabled = providers.gradleProperty("go.coverage").map { it.toBoolean() }.getOrElse(false)
val goCoverDirPath = layout.buildDirectory.dir("go-coverage").get().asFile.absolutePath
val goCoverOutPath = layout.buildDirectory.dir("go-coverage").get().asFile.resolve("coverage.out").absolutePath
val goShowcaseContainerImage = "stove-go-showcase-container:local"

tasks.register<Exec>("goModTidy") {
  description = "Runs go mod tidy to sync dependencies."
  group = "build"
  commandLine(goExecutable, "mod", "tidy")
  inputs.files("go.mod", "go.sum")
  outputs.files("go.mod", "go.sum")
}

tasks.register<Exec>("buildGoApp") {
  description = "Compiles the Go application."
  group = "build"
  dependsOn("goModTidy")
  val args = mutableListOf(goExecutable, "build")
  if (coverageEnabled) args.add("-cover")
  args.addAll(listOf("-o", goBinary.absolutePath, "."))
  commandLine(args)
  inputs.files(fileTree(".") { include("*.go", "go.mod", "go.sum") })
  inputs.property("goExecutable", goExecutable)
  outputs.file(goBinary)
}

tasks.register<Exec>("buildContainerImage") {
  description = "Builds the Go showcase Docker image."
  group = "build"
  dependsOn("goModTidy")
  val buildFlags = if (coverageEnabled) "-cover" else ""
  commandLine(
    dockerExecutable,
    "build",
    "--file",
    projectDir.resolve("Dockerfile.container").absolutePath,
    "--tag",
    goShowcaseContainerImage,
    "--build-arg",
    "GO_BUILD_FLAGS=$buildFlags",
    projectDir.absolutePath
  )
  inputs.file(project.file("Dockerfile.container"))
  inputs.file(project.file(".dockerignore"))
  inputs.files(fileTree(".") { include("*.go", "go.mod", "go.sum") })
  inputs.property("coverageEnabled", coverageEnabled)
  outputs.upToDateWhen { false }
}

val removeContainerImageTask = tasks.register<Exec>("removeContainerImage") {
  description = "Removes the local Go showcase Docker image."
  group = "build"
  commandLine(dockerExecutable, "image", "rm", goShowcaseContainerImage)
  isIgnoreExitValue = true
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
    systemProperty("go.aut.mode", "process")
    systemProperty("go.app.binary", goBinary.absolutePath)
    systemProperty("kafka.library", lib)
    if (coverageEnabled) {
      systemProperty("go.cover.dir", goCoverDirPath)
      outputs.cacheIf { false } // Coverage data is a side effect, not a tracked output
    }
    if (index > 0) mustRunAfter("e2eTest_${kafkaLibraries[index - 1]}")
  }
}

tasks.register<Test>("e2eTest") {
  description = "Runs e2e tests for all Kafka libraries."
  group = "verification"
  dependsOn(kafkaE2eTasks)
  enabled = false
}

val containerE2eTask = tasks.register<Test>("e2eTest-container") {
  description = "Runs container-based e2e tests with sarama Kafka library."
  group = "verification"
  dependsOn("buildContainerImage")
  testClassesDirs = sourceSets[stoveTests].output.classesDirs
  classpath = sourceSets[stoveTests].runtimeClasspath
  useJUnitPlatform()
  systemProperty("go.aut.mode", "container")
  systemProperty("go.app.container.image", goShowcaseContainerImage)
  systemProperty("kafka.library", "sarama")
  if (coverageEnabled) {
    systemProperty("go.cover.dir", goCoverDirPath)
    outputs.cacheIf { false } // Coverage data is a side effect, not a tracked output
  }
}

// -- Go coverage reports ------------------------------------------------------
if (coverageEnabled) {
  val goCoverHtmlPath = layout.buildDirectory.dir("go-coverage").get().asFile.resolve("coverage.html").absolutePath

  tasks.register<Exec>("goCoverageReport") {
    description = "Converts Go coverage data to standard format."
    group = "verification"
    mustRunAfter(kafkaE2eTasks)
    mustRunAfter(containerE2eTask)
    commandLine(goExecutable, "tool", "covdata", "textfmt", "-i=$goCoverDirPath", "-o=$goCoverOutPath")
  }

  tasks.register<Exec>("goCoverageSummary") {
    description = "Prints Go coverage summary."
    group = "verification"
    dependsOn("goCoverageReport")
    commandLine(goExecutable, "tool", "cover", "-func=$goCoverOutPath")
  }

  tasks.register<Exec>("goCoverageHtml") {
    description = "Generates HTML coverage report."
    group = "verification"
    dependsOn("goCoverageReport")
    commandLine(goExecutable, "tool", "cover", "-html=$goCoverOutPath", "-o=$goCoverHtmlPath")
    doLast { logger.lifecycle("Go coverage HTML: $goCoverHtmlPath") }
  }

  tasks.register("e2eTestWithCoverage") {
    description = "Runs e2e tests and generates Go coverage report."
    group = "verification"
    dependsOn(kafkaE2eTasks)
    finalizedBy("goCoverageSummary", "goCoverageHtml")
  }

  tasks.register("e2eTest-containerWithCoverage") {
    description = "Runs container e2e tests and generates Go coverage report."
    group = "verification"
    dependsOn(containerE2eTask)
    finalizedBy("goCoverageSummary", "goCoverageHtml")
  }
}

// -- Dependencies -------------------------------------------------------------
dependencies {
  testImplementation(stoveLibs.stove)
  testImplementation("com.trendyol:stove-container:${libs.versions.stove.get()}")
  testImplementation(stoveLibs.stoveProcess)
  testImplementation(stoveLibs.stovePostgres)
  testImplementation(stoveLibs.stoveHttp)
  testImplementation(stoveLibs.stoveTracing)
  testImplementation(stoveLibs.stoveDashboard)
  testImplementation(stoveLibs.stoveKafka)
  testImplementation(stoveLibs.stoveExtensionsKotest)

  testImplementation(libs.kotest.runner.junit6)
  testImplementation(libs.kotest.framework.engine)
  testImplementation(libs.kotest.assertions.core)
}

// -- Kotlin / Java settings ---------------------------------------------------
kotlin { jvmToolchain(21) }

tasks.withType<Test> {
  useJUnitPlatform()
  jvmArgs("--add-opens", "java.base/java.util=ALL-UNNAMED")
}
