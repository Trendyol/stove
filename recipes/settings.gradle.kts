@file:Suppress("UnstableApiUsage")

rootProject.name = "recipes"

include(
  "kotlin-recipes",
  "kotlin-recipes:ktor-recipe",
  "kotlin-recipes:spring-boot-recipe",
  "java-recipes",
  "java-recipes:spring-boot-recipe",
  "java-recipes:quarkus-recipe",
  "scala-recipes",
  "scala-recipes:spring-boot-recipe",
  "shared",
  "shared:domain",
  "shared:application",
  "shared:infra",
)

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")
dependencyResolutionManagement {
  repositories {
    mavenCentral()
    maven("https://oss.sonatype.org/content/repositories/snapshots")
  }
}
