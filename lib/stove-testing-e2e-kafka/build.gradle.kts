dependencies {
    api(project(":lib:stove-testing-e2e"))
    implementation(libs.kafka)
    implementation(libs.kotlinx.io.reactor.extensions)
    implementation(libs.kotlinx.jdk8)
    implementation(libs.kotlinx.core)
    implementation(libs.kafkaKotlin)
    implementation(testLibs.testcontainers.kafka)
}

dependencies {
    testImplementation(libs.slf4j.simple)
}
