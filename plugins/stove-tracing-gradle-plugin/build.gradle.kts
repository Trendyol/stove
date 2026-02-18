@file:Suppress("UnstableApiUsage")

import java.util.Properties

val rootProps = Properties().apply {
  val f = file("../../gradle.properties")
  if (f.exists()) f.reader().use { load(it) }
}
fun prop(key: String): String = rootProps.getProperty(key)
  ?: error("Property '$key' not found in gradle.properties")

group = "com.trendyol"
version = prop("version")

plugins {
  `java-gradle-plugin`
  `kotlin-dsl`
  alias(libs.plugins.maven.publish)
  alias(libs.plugins.plugin.publish)
}

gradlePlugin {
  website.set("https://github.com/Trendyol/stove")
  vcsUrl.set("https://github.com/Trendyol/stove")

  plugins {
    create("stoveTracing") {
      id = "com.trendyol.stove.tracing"
      implementationClass = "com.trendyol.stove.gradle.StoveTracingPlugin"
      displayName = "Stove Tracing Plugin"
      description = "Configures OpenTelemetry Java Agent for Stove test tracing"
      tags.set(listOf("testing", "e2e", "opentelemetry", "tracing", "stove"))
    }
  }
}

mavenPublishing {
  coordinates(
    groupId = rootProject.group.toString(),
    artifactId = project.name,
    version = rootProject.version.toString()
  )
  publishToMavenCentral()
  pom {
    name.set(project.name)
    description.set("Gradle plugin that configures OpenTelemetry Java Agent for Stove test tracing")
    url.set(prop("projectUrl"))
    licenses {
      license {
        name.set(prop("licence"))
        url.set(prop("licenceUrl"))
      }
    }
    developers {
      developer {
        id.set("osoykan")
        name.set("Oguzhan Soykan")
        email.set("oguzhan.soykan@trendyol.com")
      }
    }
    scm {
      connection.set("scm:git@github.com:Trendyol/stove.git")
      developerConnection.set("scm:git:ssh://github.com:Trendyol/stove.git")
      url.set(prop("projectUrl"))
    }
  }
  signAllPublications()
}

tasks.test {
  useJUnitPlatform()
}

dependencies {
  testImplementation(libs.kotest.runner.junit5)
  testImplementation(libs.kotest.framework.engine)
  testImplementation(libs.kotest.assertions.core)
}
