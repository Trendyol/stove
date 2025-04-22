import org.gradle.plugins.ide.idea.model.IdeaModel
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  kotlin("jvm").version(libs.versions.kotlin)
  alias(libs.plugins.spotless)
  alias(libs.plugins.testLogger)
  alias(libs.plugins.kover)
  alias(libs.plugins.detekt)
  alias(libs.plugins.binaryCompatibilityValidator)
  id("stove-publishing") apply false
  idea
  java
}

group = "com.trendyol"
version = CI.version(project)

apiValidation {
  ignoredProjects += listOf(
    "ktor-example",
//    "micronaut-example",
    "spring-example",
    "spring-standalone-example",
    "spring-streams-example"
  )
}
kover {
  reports {
    filters {
      excludes {
        classes(
          "com.trendyol.stove.functional.*",
          "com.trendyol.stove.testing.e2e.system.abstractions.*",
          "com.trendyol.stove.testing.e2e.system.annotations.*",
          "com.trendyol.stove.testing.e2e.serialization.*",
          "com.trendyol.stove.testing.e2e.standalone.*",
          "com.trendyol.stove.testing.e2e.streams.*",
          "stove.spring.example.*",
          "stove.spring.standalone.example.*",
          "stove.spring.streams.example.*",
          "stove.ktor.example.*"
        )
      }
    }
  }
}
val related = subprojects.of("lib", "spring", "examples", "ktor", "micronaut")
dependencies {
  related.forEach {
    kover(it)
  }
}

subprojects.of("lib", "spring", "examples", "ktor", "micronaut") {
  apply {
    plugin("kotlin")
    plugin(rootProject.libs.plugins.spotless.get().pluginId)
    plugin(rootProject.libs.plugins.testLogger.get().pluginId)
    plugin(rootProject.libs.plugins.kover.get().pluginId)
    plugin(rootProject.libs.plugins.detekt.get().pluginId)
    plugin("idea")
  }

  val testImplementation by configurations
  val libs = rootProject.libs
  dependencies {
    api(libs.arrow.core)
  }

  detekt {
    buildUponDefaultConfig = true
    parallel = true
    config.from(rootProject.file("detekt.yml"))
  }
  dependencies {
    testImplementation(kotlin("test"))
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.property)
    detektPlugins(libs.detekt.formatting)
  }

  spotless {
    kotlin {
      ktlint().setEditorConfigPath(rootProject.layout.projectDirectory.file(".editorconfig"))
      targetExclude("build/", "generated/", "out/")
      targetExcludeIfContentContains("generated")
      targetExcludeIfContentContainsRegex("generated.*")
    }
  }
  the<IdeaModel>().apply {
    module {
      isDownloadSources = true
      isDownloadJavadoc = true
    }
  }

  tasks {
    compileKotlin {
      incremental = true
    }
    test {
      useJUnitPlatform()
      testlogger {
        setTheme("mocha")
        showStandardStreams = !runningOnCI
        showExceptions = true
        showCauses = true
      }
      reports {
        junitXml.required.set(true)
      }
      jvmArgs("--add-opens", "java.base/java.util=ALL-UNNAMED")
    }
    kotlin {
      jvmToolchain(17)
      compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        allWarningsAsErrors = true
        freeCompilerArgs.addAll(
          "-Xjsr305=strict",
          "-Xcontext-receivers",
          "-Xsuppress-version-warnings"
        )
      }
    }
  }
}

val publishedProjects = listOf(
  "stove-testing-e2e",
  "stove-testing-e2e-couchbase",
  "stove-testing-e2e-elasticsearch",
  "stove-testing-e2e-http",
  "stove-testing-e2e-kafka",
  "stove-testing-e2e-mongodb",
  "stove-testing-e2e-rdbms",
  "stove-testing-e2e-rdbms-postgres",
  "stove-testing-e2e-rdbms-mssql",
  "stove-testing-e2e-wiremock",
  "stove-testing-e2e-redis",
  "stove-ktor-testing-e2e",
  "stove-spring-testing-e2e",
  "stove-spring-testing-e2e-kafka",
  "stove-micronaut-testing-e2e"
)

subprojects.of("lib", "spring", "ktor", "micronaut", filter = { p -> publishedProjects.contains(p.name) }) {
  apply {
    plugin("java")
    plugin("stove-publishing")
  }

  java {
    withSourcesJar()
    withJavadocJar()
  }
}
