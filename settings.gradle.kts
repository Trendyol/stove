@file:Suppress("UnstableApiUsage")
rootProject.name = "stove4k"
include(
    "lib",
    "lib:stove-testing-e2e",
    "lib:stove-testing-e2e-wiremock",
    "lib:stove-testing-e2e-http",
    "lib:stove-testing-e2e-kafka",
    "lib:stove-testing-e2e-couchbase"
)

include(
    "starters",
    "starters:spring:stove-spring-testing-e2e",
    "starters:spring:stove-spring-testing-e2e-kafka"
)

include(
    "examples",
    "examples:spring-example"
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
