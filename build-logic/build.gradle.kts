plugins {
  `kotlin-dsl`
}

repositories {
  mavenCentral()
  google()
  gradlePluginPortal()
}

sourceSets {
  main {
    // Expose the publishable tracing plugin locally to the main build.
    kotlin.srcDir("../plugins/stove-tracing-gradle-plugin/src/main/kotlin")
  }
}

gradlePlugin {
  plugins {
    create("stoveBuildLogic") {
      id = "com.trendyol.stove.build-logic"
      implementationClass = "com.trendyol.stove.gradle.StoveBuildLogicPlugin"
    }
    create("stoveTracing") {
      id = "com.trendyol.stove.tracing"
      implementationClass = "com.trendyol.stove.gradle.StoveTracingPlugin"
    }
  }
}

// The local plugin uses the same catalog defaults as the published plugin.
val profilingDefaults = tasks.register<WriteProperties>("profilingDefaults") {
  destinationFile.set(layout.buildDirectory.file("generated/profiling/stove-profiling.properties"))
  property("agentVersion", libs.versions.pyroscope.get())
}
tasks.processResources {
  from(profilingDefaults)
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
dependencies {
  add(pyroscopeOtelExtension.name, "io.pyroscope:pyroscope-otel-javaagent-extension:${libs.versions.pyroscopeOtelExtension.get()}")
}
tasks.processResources {
  // Bundle it as a resource, never as Gradle's runtime instrumentation.
  from(pyroscopeOtelExtension) {
    into("profiling")
    rename { "pyroscope-otel-javaagent-extension.jar" }
  }
}
