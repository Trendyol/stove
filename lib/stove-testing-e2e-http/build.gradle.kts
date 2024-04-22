dependencies {
    api(projects.lib.stoveTestingE2e)
    api(libs.ktor.client.core)
    api(libs.ktor.client.okhttp)
    implementation(libs.kotlinx.core)
    implementation(libs.kotlinx.io.reactor)
    implementation(libs.kotlinx.reactive)
    implementation(libs.kotlinx.jdk8)
    implementation(libs.ktor.client.plugins.logging)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.jackson.json)
    testImplementation(projects.lib.stoveTestingE2eWiremock)
    testImplementation(libs.jackson.jsr310)
}
