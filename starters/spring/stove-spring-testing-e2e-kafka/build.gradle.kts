dependencies {
    api(project(":lib:stove-testing-e2e"))
    implementation(libs.spring.framework.context)
    implementation(libs.spring.boot.kafka)
    implementation(libs.spring.boot.itself)
    implementation(testLibs.testcontainers.kafka)
    implementation(libs.kafkaKotlin)

    testAnnotationProcessor(libs.spring.boot.annotationProcessor)
    testImplementation(libs.spring.boot.autoconfigure)
    testImplementation(project(":starters:spring:stove-spring-testing-e2e"))
    testImplementation(libs.slf4j.simple)
}
