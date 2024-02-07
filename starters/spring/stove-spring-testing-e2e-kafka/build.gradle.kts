dependencies {
    api(projects.lib.stoveTestingE2e)
    implementation(libs.spring.framework.context)
    implementation(libs.spring.boot.kafka)
    implementation(libs.spring.boot.itself)
    implementation(libs.testcontainers.kafka)
    implementation(libs.kafkaKotlin)

    testAnnotationProcessor(libs.spring.boot.annotationProcessor)
    testImplementation(libs.spring.boot.autoconfigure)
    testImplementation(projects.starters.spring.stoveSpringTestingE2e)
    testImplementation(libs.slf4j.simple)
}
