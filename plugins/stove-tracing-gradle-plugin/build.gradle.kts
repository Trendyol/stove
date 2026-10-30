@file:Suppress("UnstableApiUsage")

plugins {
  `kotlin-dsl`
  `java-gradle-plugin`
  alias(libs.plugins.maven.publish)
}

gradlePlugin {
  plugins {
    create("stoveTracing") {
      id = "com.trendyol.stove.tracing"
      implementationClass = "com.trendyol.stove.gradle.StoveTracingPlugin"
    }
  }
}

java {
  toolchain {
    languageVersion.set(JavaLanguageVersion.of(17))
  }
}

tasks.test {
  useJUnitPlatform()
}

dependencies {
  testImplementation(libs.kotest.runner.junit6)
  testImplementation(libs.kotest.framework.engine)
  testImplementation(libs.kotest.assertions.core)
}

// Package catalog defaults so downstream builds do not need Stove's version catalog.
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
