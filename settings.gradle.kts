@file:Suppress("UnstableApiUsage")
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")
rootProject.name = "stove"
include(
  "lib:stove-bom",
  "lib:stove",
  "lib:stove-wiremock",
  "lib:stove-http",
  "lib:stove-grpc",
  "lib:stove-kafka",
  "lib:stove-couchbase",
  "lib:stove-rdbms",
  "lib:stove-postgres",
  "lib:stove-mssql",
  "lib:stove-elasticsearch",
  "lib:stove-redis",
  "lib:stove-mongodb"
)
include(
  "test-extensions:stove-extensions-kotest",
  "test-extensions:stove-extensions-junit"
)
include(
  "starters:ktor:stove-ktor",
  "starters:ktor:tests:ktor-test-fixtures",
  "starters:ktor:tests:ktor-koin-tests",
  "starters:ktor:tests:ktor-di-tests",
  "starters:spring:stove-spring",
  "starters:spring:stove-spring-kafka",
  "starters:spring:tests:spring-test-fixtures",
  "starters:spring:tests:spring-2x-tests",
  "starters:spring:tests:spring-2x-kafka-tests",
  "starters:spring:tests:spring-3x-tests",
  "starters:spring:tests:spring-3x-kafka-tests",
  "starters:spring:tests:spring-4x-tests",
  "starters:spring:tests:spring-4x-kafka-tests",
  "starters:micronaut:stove-micronaut"
)
include(
  "examples:spring-example",
  "examples:spring-standalone-example",
  "examples:spring-4x-example",
  "examples:ktor-example",
  "examples:spring-streams-example",
  "examples:micronaut-example"
)
dependencyResolutionManagement {
  repositories {
    mavenCentral()
    maven {
      url = uri("https://packages.confluent.io/maven/")
    }
  }
}
plugins {
  id("org.danilopianini.gradle-pre-commit-git-hooks").version("2.1.6")
}
gitHooks {
  preCommit {
    from(rootDir.resolve("pre-commit.sh"))
  }
  createHooks(overwriteExisting = true)
}
