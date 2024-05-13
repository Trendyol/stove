@file:Suppress("UnstableApiUsage")

rootProject.name = "recipes"

include(
  "shared-domain",
  "kotlin-recipes",
  "kotlin-recipes:ktor-recipe",
  "kotlin-recipes:spring-boot-recipe",
  "java-recipes",
  "java-recipes:spring-boot-recipe"
)

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")
dependencyResolutionManagement {
  repositories {
    mavenCentral()
    maven("https://oss.sonatype.org/content/repositories/snapshots")
  }
}
