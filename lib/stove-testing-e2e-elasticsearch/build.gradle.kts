plugins {}
dependencies {
    api(projects.lib.stoveTestingE2e)
    api(libs.elastic)
    implementation(libs.testcontainers.elasticsearch)
    implementation(libs.jackson.databind)
    implementation(libs.jackson.arrow)
}

dependencies {
    testImplementation(libs.slf4j.simple)
}
