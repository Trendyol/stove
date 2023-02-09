plugins {}
dependencies {
    api(project(":lib:stove-testing-e2e"))
    api(libs.elastic)
    implementation(testLibs.testcontainers.elasticsearch)
    implementation(libs.jackson.databind)
}

dependencies {
    testImplementation(libs.slf4j.simple)
}
