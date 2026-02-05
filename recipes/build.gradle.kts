import org.gradle.plugins.ide.idea.model.IdeaModel
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  kotlin("jvm").version(libs.versions.kotlin)
  alias(libs.plugins.spotless)
  alias(libs.plugins.testLogger)
  alias(libs.plugins.detekt)
  idea
  java
}

subprojects {
  apply {
    plugin(rootProject.libs.plugins.spotless.get().pluginId)
    plugin(rootProject.libs.plugins.testLogger.get().pluginId)
    plugin(rootProject.libs.plugins.detekt.get().pluginId)
    plugin("idea")
    plugin("java")
    plugin("kotlin")
  }

  detekt {
    buildUponDefaultConfig = true
    parallel = true
    config.from(rootProject.file("detekt.yml"))
  }

  dependencies {
    testImplementation(rootProject.libs.kotest.framework.engine)
    testImplementation(rootProject.libs.kotest.assertions.core)
    testImplementation(rootProject.libs.kotest.runner.junit5)
    detektPlugins(rootProject.libs.detekt.formatting)
  }

  spotless {
    java {
      palantirJavaFormat("2.86.0").style("AOSP").formatJavadoc(true)
      targetExcludeIfContentContains("generated")
      targetExclude("build/**", "**/build/**", "**/generated/**")
      targetExcludeIfContentContainsRegex(".*generated.*")
    }

    scala {
      scalafmt("3.10.6")
    }

    kotlin {
      ktlint(libs.versions.ktlint.get())
        .setEditorConfigPath(rootProject.layout.projectDirectory.file(".editorconfig"))
      targetExclude("build/**", "**/build/**", "**/generated/**")
      targetExcludeIfContentContains("generated")
      targetExcludeIfContentContainsRegex(".*generated.*")
    }
  }

  the<IdeaModel>().apply {
    module {
      isDownloadSources = true
      isDownloadJavadoc = true
    }
  }

  tasks {
    test {
      dependsOn(spotlessApply)
      useJUnitPlatform()
      testlogger {
        setTheme("mocha")
        showStandardStreams = true
        showExceptions = true
        showCauses = true
      }
      reports {
        junitXml.required.set(true)
      }
      jvmArgs("--add-opens", "java.base/java.util=ALL-UNNAMED")
    }

    kotlin {
      jvmToolchain(21)
    }
    java {
      sourceCompatibility = JavaVersion.VERSION_21
      targetCompatibility = JavaVersion.VERSION_21
    }

    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
      compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
        allWarningsAsErrors = true
        freeCompilerArgs.addAll(
          "-Xjsr305=strict",
          "-Xcontext-parameters",
          "-Xsuppress-version-warnings"
        )
      }
    }
  }
}
