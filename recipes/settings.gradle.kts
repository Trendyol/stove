@file:Suppress("UnstableApiUsage")

import dev.aga.gradle.versioncatalogs.Generator.generate
import dev.aga.gradle.versioncatalogs.GeneratorConfig

rootProject.name = "recipes"

include(
  "kotlin-recipes",
  "kotlin-recipes:ktor-mongo-recipe",
  "kotlin-recipes:ktor-postgres-recipe",
  "kotlin-recipes:spring-showcase",
  "java-recipes",
  "java-recipes:spring-boot-postgres-recipe",
  "java-recipes:quarkus-basic-recipe",
  "scala-recipes",
  "scala-recipes:spring-boot-basic-recipe",
  "shared",
  "shared:domain",
  "shared:application",
)
plugins {
  id("dev.aga.gradle.version-catalog-generator") version ("4.0.0")
}
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")
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
    generate("stoveLibs") {
      fromToml("stove-bom") {
        aliasPrefixGenerator = GeneratorConfig.NO_PREFIX // (8)
      }
    }
  }
}
