dependencies {
    api(projects.lib.stoveTestingE2e)
    implementation(libs.kotlinx.core)
    implementation(libs.kotlinx.io.reactor)
    implementation(libs.kotlinx.reactive)
    implementation(libs.kotlinx.jdk8)
    testImplementation(projects.lib.stoveTestingE2eWiremock)
}
