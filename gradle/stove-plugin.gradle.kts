import org.gradle.language.jvm.tasks.ProcessResources
import org.gradle.plugin.devel.GradlePluginDevelopmentExtension

// Both the included build and the published plugin must expose the same marker and resources.
configure<GradlePluginDevelopmentExtension> {
  plugins.create("stove") {
    id = "com.trendyol.stove"
    implementationClass = "com.trendyol.stove.gradle.StovePlugin"
  }
}

val catalog = extensions.getByType<VersionCatalogsExtension>().named("libs")
val generator = catalog.findLibrary("openapi-generator").get()
dependencies.add("compileOnly", generator)

val defaults = tasks.register<WriteProperties>("stovePluginDefaults") {
  destinationFile.set(layout.buildDirectory.file("generated/stove/stove-plugin.properties"))
  property("openapiGeneratorVersion", generator.get().versionConstraint.requiredVersion)
  property("agentVersion", catalog.findVersion("pyroscope").get().requiredVersion)
}

// Upstream publishes the shaded OTel extension on GitHub, not Maven Central.
repositories {
  mavenCentral()
  ivy {
    name = "pyroscopeOtelReleases"
    url = uri("https://github.com/grafana/otel-profiling-java/releases/download")
    patternLayout { artifact("v[revision]/[artifact].[ext]") }
    metadataSources { artifact() }
    content { includeModule("io.pyroscope", "pyroscope-otel-javaagent-extension") }
  }
}
val pyroscopeOtelExtension = configurations.create("pyroscopeOtelExtension") {
  isTransitive = false
  isCanBeConsumed = false
}
val extensionVersion = catalog.findVersion("pyroscopeOtelExtension").get().requiredVersion
dependencies.add(pyroscopeOtelExtension.name, "io.pyroscope:pyroscope-otel-javaagent-extension:$extensionVersion")

tasks.named<ProcessResources>("processResources") {
  from(defaults)
  from(pyroscopeOtelExtension) {
    into("profiling")
    rename { "pyroscope-otel-javaagent-extension.jar" }
  }
}
