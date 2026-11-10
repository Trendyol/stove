@file:Suppress("UnstableApiUsage")

import dev.aga.gradle.versioncatalogs.Generator.generate
import dev.aga.gradle.versioncatalogs.GeneratorConfig

rootProject.name = "jvm-recipes"
pluginManagement {
  includeBuild("../../build-logic/test-conventions")

  repositories {
    gradlePluginPortal()
    mavenCentral()
    maven("https://central.sonatype.com/repository/maven-snapshots")
  }
}
include(
  "kotlin-recipes",
  "kotlin-recipes:ktor-mongo-recipe",
  "kotlin-recipes:ktor-postgres-recipe",
  "kotlin-recipes:spring-showcase",
  "java-recipes",
  "java-recipes:spring-boot-postgres-recipe",
  "java-recipes:quarkus-basic-recipe",
  "java-recipes:micronaut-postgres-recipe",
  "scala-recipes",
  "scala-recipes:spring-boot-basic-recipe",
  "shared",
  "shared:domain",
  "shared:application",
)
plugins {
  id("dev.aga.gradle.version-catalog-generator") version ("4.2.3")
}

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")
val stoveVersion = "1.0.0.572-SNAPSHOT"

dependencyResolutionManagement {
  repositories {
    mavenCentral()
    maven("https://central.sonatype.com/repository/maven-snapshots") {
      content {
        includeGroup("com.trendyol")
      }
    }
  }

  versionCatalogs {
    create("libs") {
      from(files("../../gradle/libs.versions.toml"))
      version("stove", stoveVersion)
    }
    generate("stoveLibs") {
      from("com.trendyol:stove-bom:$stoveVersion")
      using {
        aliasPrefixGenerator = GeneratorConfig.NO_PREFIX // (8)
      }
    }
  }
}
