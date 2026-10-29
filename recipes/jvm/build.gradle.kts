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

// Recipe tests remain sequential inside each JVM, so spend the budget on modules.
abstract class RecipeTestSlots : BuildService<BuildServiceParameters.None>

val recipeTestBudget = providers.gradleProperty("stove.test.cpuBudget")
  .map { it.toInt().also { value -> require(value > 0) } }
  .getOrElse((Runtime.getRuntime().availableProcessors() * 0.8).toInt().coerceAtLeast(1))
val recipeTestSlots = gradle.sharedServices.registerIfAbsent("recipeTestSlots", RecipeTestSlots::class) {
  maxParallelUsages.set(recipeTestBudget)
}

subprojects {
  if (subprojects.isNotEmpty()) {
    return@subprojects
  }

  apply {
    plugin(rootProject.libs.plugins.spotless.get().pluginId)
    plugin(rootProject.libs.plugins.testLogger.get().pluginId)
    plugin(rootProject.libs.plugins.detekt.get().pluginId)
    plugin("idea")
    plugin("java")
    plugin("kotlin")
  }

  extra["kotlin-coroutines.version"] = rootProject.libs.versions.kotlinx.asProvider().get()

  detekt {
    buildUponDefaultConfig = true
    parallel = true
    config.from(rootProject.file("detekt.yml"))
  }

  dependencies {
    testImplementation(rootProject.libs.kotest.framework.engine)
    testImplementation(rootProject.libs.kotest.assertions.core)
    testImplementation(rootProject.libs.kotest.runner.junit6)
    detektPlugins(rootProject.libs.detekt.formatting)
  }

  spotless {
    java {
      target("src/**/*.java")
      palantirJavaFormat("2.86.0").style("GOOGLE").formatJavadoc(true)
      targetExcludeIfContentContains("generated")
      targetExclude("build/**", "**/build/**", "generated/**", "**/generated/**", "out/**", "**/out/**")
      targetExcludeIfContentContainsRegex(".*generated.*")
    }

    scala {
      scalafmt("3.10.6")
    }

    kotlin {
      target("src/**/*.kt")
      ktlint(libs.versions.ktlint.get())
        .setEditorConfigPath(rootProject.layout.projectDirectory.file(".editorconfig"))
      targetExclude("build/**", "**/build/**", "generated/**", "**/generated/**", "out/**", "**/out/**")
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
    withType<Test>().configureEach {
      // Suites share Stove and mutable fixtures inside a JVM; parallelize projects instead.
      maxParallelForks = 1
      usesService(recipeTestSlots)
      doFirst {
        val isolatedTemp = temporaryDir.resolve("tmp").apply { mkdirs() }
        systemProperty("java.io.tmpdir", isolatedTemp.absolutePath)
      }
    }

    if (JavaVersion.current().isCompatibleWith(JavaVersion.toVersion("25"))) {
      withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
        enabled = false
      }
    }

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
          "-Xsuppress-version-warnings"
        )
      }
    }
  }
}
