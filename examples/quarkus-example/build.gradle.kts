import com.trendyol.stove.gradle.stoveTracing
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

@DisableCachingByDefault(because = "Creates an empty classes directory required by Quarkus code generation")
abstract class EnsureDirectoryTask : DefaultTask() {
  @get:OutputDirectory
  abstract val outputDirectory: DirectoryProperty

  @TaskAction
  fun createDirectory() {
    outputDirectory.get().asFile.mkdirs()
  }
}

plugins {
  alias(libs.plugins.quarkus)
  alias(libs.plugins.allopen)
  idea
  application
}

dependencies {
  implementation(enforcedPlatform(libs.quarkus))
  implementation(libs.quarkus.rest)
  implementation(libs.quarkus.rest.jackson)
  implementation(libs.quarkus.arc)
  implementation(libs.quarkus.kotlin)
  implementation(libs.quarkus.agroal)
  implementation(libs.quarkus.jdbc.postgresql)
  implementation(libs.quarkus.flyway)
  implementation(libs.quarkus.messaging.kafka)
  implementation(libs.jackson.kotlin)
  implementation(libs.opentelemetry.instrumentation.annotations)
}

dependencies {
  testImplementation(projects.stove.testExtensions.stoveExtensionsKotest)
  testImplementation(projects.stove.lib.stoveHttp)
  testImplementation(projects.stove.lib.stoveWiremock)
  testImplementation(projects.stove.lib.stovePostgres)
  testImplementation(projects.stove.lib.stoveKafka)
  testImplementation(projects.stove.lib.stoveTracing)
  testImplementation(projects.stove.starters.quarkus.stoveQuarkus)
}

allOpen {
  annotation("jakarta.ws.rs.Path")
  annotation("jakarta.enterprise.context.ApplicationScoped")
}

application {
  mainClass.set("stove.quarkus.example.QuarkusMainApp")
}

val ensureJavaMainClassesDir by tasks.registering(EnsureDirectoryTask::class) {
  outputDirectory.set(layout.buildDirectory.dir("classes/java/main"))
}

tasks.matching { it.name == "quarkusGenerateCode" || it.name == "quarkusGenerateCodeTests" }.configureEach {
  dependsOn(ensureJavaMainClassesDir)
}

tasks.named("test") {
  dependsOn("quarkusBuild")
}

kotlin {
  compilerOptions {
    javaParameters = true
  }
}

stoveTracing {
  serviceName = "quarkus-example"
}
