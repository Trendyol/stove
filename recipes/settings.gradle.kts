@file:Suppress("UnstableApiUsage")

rootProject.name = "recipes"

include(
  "kotlin-recipes",
  "kotlin-recipes:ktor-mongo-recipe",
  "kotlin-recipes:ktor-postgres-recipe",
  "kotlin-recipes:spring-boot-basic-recipe",
  "java-recipes",
  "java-recipes:spring-boot-couchbase-recipe",
  "java-recipes:quarkus-basic-recipe",
  "scala-recipes",
  "scala-recipes:spring-boot-basic-recipe",
  "shared",
  "shared:domain",
  "shared:application",
)

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")
dependencyResolutionManagement {
  repositories {
    mavenCentral()
    maven("https://oss.sonatype.org/content/repositories/snapshots")
  }
}
