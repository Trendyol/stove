plugins {}
dependencies {
    api(project(":lib:stove-testing-e2e"))
    implementation(libs.elastic)
    implementation(testLibs.testcontainers.elasticsearch)
    implementation(libs.jackson.databind)
    implementation(libs.jackson.arrow)
}

dependencies {
    testImplementation(libs.slf4j.simple)
}
