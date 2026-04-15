val goSourceDir = project.file("product-app-go")
val goBinary = project.layout.buildDirectory.file("go-app").get().asFile

tasks.register<Exec>("buildGoApp") {
  description = "Compiles the Go application."
  group = "build"
  workingDir = goSourceDir
  commandLine("go", "build", "-o", goBinary.absolutePath, ".")

  inputs.files(
    fileTree(goSourceDir) { include("*.go", "go.mod", "go.sum") },
    fileTree(project.rootDir.resolve("go/stove-kafka")) { include("*.go", "go.mod") }
  )
  outputs.file(goBinary)
}

val kafkaLibraries = listOf("sarama", "franz", "segmentio")

val kafkaE2eTasks = kafkaLibraries.mapIndexed { index, lib ->
  tasks.register<Test>("e2eTest_$lib") {
    description = "Runs e2e tests with the $lib Kafka library."
    group = "verification"
    dependsOn("buildGoApp")
    testClassesDirs = sourceSets[TestFolders.e2e].output.classesDirs
    classpath = sourceSets[TestFolders.e2e].runtimeClasspath
    useJUnitPlatform()
    systemProperty("go.app.binary", goBinary.absolutePath)
    systemProperty("kafka.library", lib)
    if (index > 0) {
      mustRunAfter("e2eTest_${kafkaLibraries[index - 1]}")
    }
  }
}

tasks.named<Test>("e2eTest") {
  dependsOn(kafkaE2eTasks)
  enabled = false
}

dependencies {
  testImplementation(stoveLibs.stove)
  testImplementation(stoveLibs.stovePostgres)
  testImplementation(stoveLibs.stoveHttp)
  testImplementation(stoveLibs.stoveTracing)
  testImplementation(stoveLibs.stoveDashboard)
  testImplementation(stoveLibs.stoveKafka)
  testImplementation(stoveLibs.stoveExtensionsKotest)
}
