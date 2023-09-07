@Suppress("DSL_SCOPE_VIOLATION")
plugins {
    alias(libs.plugins.spring.plugin)
    alias(libs.plugins.spring.boot)
    idea
    application
}

dependencies {
    annotationProcessor(libs.spring.boot.annotationProcessor)
    implementation(libs.spring.boot.autoconfigure)
    implementation(libs.spring.boot.itself)
    implementation(libs.spring.boot.webflux)
    implementation(libs.spring.boot.actuator)
    implementation(libs.kotlinx.reactor)
    implementation(libs.kotlinx.core)
    implementation(libs.kotlinx.reactive)
    api(libs.spring.boot.kafka)
    api(libs.couchbase.client)
    api(libs.couchbase.client.metrics)
    implementation(libs.jackson.kotlin)
    implementation(libs.kotlinx.slf4j)
}

dependencies {
    testImplementation(testLibs.kotest.property.jvm)
    testImplementation(testLibs.kotest.runner.junit5)
    testImplementation(project(":lib:stove-testing-e2e"))
    testImplementation(project(":lib:stove-testing-e2e-http"))
    testImplementation(project(":lib:stove-testing-e2e-wiremock"))
    testImplementation(project(":lib:stove-testing-e2e-couchbase"))
    testImplementation(project(":lib:stove-testing-e2e-elasticsearch"))
    testImplementation(project(":starters:spring:stove-spring-testing-e2e"))
    testImplementation(project(":starters:spring:stove-spring-testing-e2e-kafka"))
}

application { mainClass.set("stove.spring.example.ExampleAppkt") }
