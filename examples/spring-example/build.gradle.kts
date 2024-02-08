plugins {
    alias(libs.plugins.spring.plugin)
    alias(libs.plugins.spring.boot)
    idea
    application
}

dependencies {
    implementation(libs.spring.boot.get3x())
    implementation(libs.spring.boot.get3x().autoconfigure)
    implementation(libs.spring.boot.get3x().webflux)
    implementation(libs.spring.boot.get3x().actuator)
    annotationProcessor(libs.spring.boot.get3x().annotationProcessor)
    api(libs.spring.boot.get3x().kafka)
    implementation(libs.kotlinx.reactor)
    implementation(libs.kotlinx.core)
    implementation(libs.kotlinx.reactive)
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
