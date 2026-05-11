import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

val generatedDashboardSourcesDir = layout.buildDirectory.dir("generated/source/stoveVersion/kotlin")
val stoveCompatibilityVersionValue = providers
  .fileContents(rootProject.layout.projectDirectory.file("gradle.properties"))
  .asText
  .map { gradleProperties ->
    gradleProperties
      .lineSequence()
      .first { it.startsWith("version=") }
      .substringAfter("version=")
      .trim()
  }

val generateDashboardVersionSource by tasks.registering(GenerateDashboardVersionSourceTask::class) {
  description = "Generates a source file containing the Stove version."
  stoveCompatibilityVersion.set(stoveCompatibilityVersionValue)
  outputDir.set(generatedDashboardSourcesDir)
}

extensions.configure<KotlinJvmProjectExtension> {
  sourceSets.getByName("main").kotlin.srcDir(generatedDashboardSourcesDir)
}

tasks.named("compileKotlin") {
  dependsOn(generateDashboardVersionSource)
}

tasks.named("sourcesJar") {
  dependsOn(generateDashboardVersionSource)
}

dependencies {
  api(projects.lib.stove)
  api(projects.lib.stoveDashboardApi)
  implementation(libs.io.grpc.netty)
  implementation(libs.kotlinx.core)

  testImplementation(projects.lib.stoveTracing)
}
