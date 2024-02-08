dependencies {
    api(projects.lib.stoveTestingE2e)
    implementation(libs.spring.boot.kafka)
    implementation(libs.testcontainers.kafka)
}

dependencies {
    testAnnotationProcessor(libs.spring.boot.annotationProcessor)
    testImplementation(libs.spring.boot.autoconfigure)
    testImplementation(projects.starters.spring.stoveSpringTestingE2e)
    testImplementation(libs.slf4j.simple)
}
