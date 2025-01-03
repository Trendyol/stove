@file:Suppress("UnstableApiUsage")
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")
rootProject.name = "stove"
include(
  "lib:stove-testing-e2e",
  "lib:stove-testing-e2e-wiremock",
  "lib:stove-testing-e2e-http",
  "lib:stove-testing-e2e-kafka",
  "lib:stove-testing-e2e-couchbase",
  "lib:stove-testing-e2e-rdbms",
  "lib:stove-testing-e2e-rdbms-postgres",
  "lib:stove-testing-e2e-rdbms-mssql",
  "lib:stove-testing-e2e-elasticsearch",
  "lib:stove-testing-e2e-redis",
  "lib:stove-testing-e2e-mongodb"
)
include(
  "starters:ktor:stove-ktor-testing-e2e",
  "starters:spring:stove-spring-testing-e2e",
  "starters:spring:stove-spring-testing-e2e-kafka",
  "starters:micronaut:stove-micronaut-testing-e2e"
)
include(
  "examples:spring-example",
  "examples:spring-standalone-example",
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
