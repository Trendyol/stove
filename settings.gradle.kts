@file:Suppress("UnstableApiUsage")

rootProject.name = "stove4k"
include(
    "lib",
    "lib:stove-testing-e2e",
    "lib:stove-testing-e2e-wiremock",
    "lib:stove-testing-e2e-http",
    "lib:stove-testing-e2e-kafka",
    "lib:stove-testing-e2e-couchbase",
    "lib:stove-testing-e2e-rdbms",
    "lib:stove-testing-e2e-rdbms-postgres"
    "lib:stove-testing-e2e-couchbase",
    "lib:stove-testing-e2e-elasticsearch"
)

include(
    "starters",
    "starters:ktor:stove-ktor-testing-e2e",
    "starters:spring:stove-spring-testing-e2e",
    "starters:spring:stove-spring-testing-e2e-kafka"
)

include(
    "examples",
    "examples:spring-example",
    "examples:ktor-example"
)

dependencyResolutionManagement {
    versionCatalogs {
        create("testLibs") { from(files("./gradle/libs-test.versions.toml")) }
    }

    repositories {
        mavenCentral()
        maven { url = uri("https://oss.sonatype.org/content/repositories/snapshots") }
    }
}
