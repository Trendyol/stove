dependencies {
    api(projects.lib.stoveTestingE2e)
    api(libs.testcontainers.kafka)
    implementation(libs.kafka)
    implementation(libs.kotlinx.io.reactor.extensions)
    implementation(libs.kotlinx.jdk8)
    implementation(libs.kotlinx.core)
    implementation(libs.kafkaKotlin)
}

dependencies {
    testImplementation(libs.slf4j.simple)
}
