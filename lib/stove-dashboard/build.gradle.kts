import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

val generatedDashboardSourcesDir = layout.buildDirectory.dir("generated/source/stoveVersion/kotlin")

// The root project resolves the published coordinate in CI.version: the release
// version, or `<snapshot>.<build>-SNAPSHOT` for snapshot builds. Report that
// exact version so a run and the matching server compare as the same build.
val stoveVersionValue = rootProject.version.toString()

val generateDashboardVersionSource = tasks.register<GenerateDashboardVersionSourceTask>("generateDashboardVersionSource") {
  description = "Generates a source file containing the exact published Stove version."
  stoveVersion.set(stoveVersionValue)
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
  compileOnly(libs.pyroscope.agent)
  testImplementation(libs.pyroscope.agent)
  api(projects.lib.stove)
  api(projects.lib.stoveDashboardApi)
  implementation(libs.io.grpc.netty)
  implementation(libs.kotlinx.core)
  implementation(libs.ktor.client.core)
  implementation(libs.ktor.client.cio)

  testImplementation(projects.lib.stoveTracing)
}
