val goSourceDir = project.file("product-app-go")
val goBinary = project.layout.buildDirectory.file("go-app").get().asFile

tasks.register<Exec>("buildGoApp") {
  description = "Compiles the Go application."
  group = "build"
  workingDir = goSourceDir
  commandLine("go", "build", "-o", goBinary.absolutePath, ".")

  inputs.files(fileTree(goSourceDir) { include("*.go", "go.mod", "go.sum") })
  outputs.file(goBinary)
}

tasks.named<Test>("e2eTest") {
  dependsOn("buildGoApp")
  systemProperty("go.app.binary", goBinary.absolutePath)
}

dependencies {
  testImplementation(stoveLibs.stove)
  testImplementation(stoveLibs.stovePostgres)
  testImplementation(stoveLibs.stoveHttp)
  testImplementation(stoveLibs.stoveTracing)
  testImplementation(stoveLibs.stoveDashboard)
  testImplementation(stoveLibs.stoveExtensionsKotest)
}
