@file:Suppress("UnstableApiUsage")

import dev.aga.gradle.versioncatalogs.Generator.generate
import dev.aga.gradle.versioncatalogs.GeneratorConfig

rootProject.name = "go-showcase"
val useMavenLocal = providers.gradleProperty("useMavenLocal").map(String::toBoolean).getOrElse(false)

pluginManagement {
  repositories {
    gradlePluginPortal()
    mavenCentral()
    maven("https://central.sonatype.com/repository/maven-snapshots")
  }
}

plugins {
  id("dev.aga.gradle.version-catalog-generator") version "4.2.0"
}

dependencyResolutionManagement {
  repositories {
    if (useMavenLocal) {
      mavenLocal()
    }
    mavenCentral()
    maven("https://central.sonatype.com/repository/maven-snapshots") {
      content {
        includeGroup("com.trendyol")
      }
    }
  }

  versionCatalogs {
    create("libs") {
      from(files("../../../../gradle/libs.versions.toml"))
    }
    generate("stoveLibs") {
      from {
        toml {
          libraryAliases = listOf("stove-bom")
          file = file("../../../../gradle/libs.versions.toml")
        }
      }
      using {
        aliasPrefixGenerator = GeneratorConfig.NO_PREFIX
      }
    }
  }
}
