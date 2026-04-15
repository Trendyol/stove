@file:Suppress("UnstableApiUsage")

import dev.aga.gradle.versioncatalogs.Generator.generate
import dev.aga.gradle.versioncatalogs.GeneratorConfig

rootProject.name = "go-showcase"

pluginManagement {
  repositories {
    gradlePluginPortal()
    mavenCentral()
    maven("https://central.sonatype.com/repository/maven-snapshots")
  }
}

plugins {
  id("dev.aga.gradle.version-catalog-generator") version "4.1.1"
}

dependencyResolutionManagement {
  repositories {
    mavenLocal()
    mavenCentral()
    maven("https://central.sonatype.com/repository/maven-snapshots") {
      content {
        includeGroup("com.trendyol")
      }
    }
  }

  versionCatalogs {
    generate("stoveLibs") {
      fromToml("stove-bom") {
        aliasPrefixGenerator = GeneratorConfig.NO_PREFIX
      }
    }
  }
}
