plugins {}
dependencies {
    api(project(":lib:stove-testing-e2e"))
    implementation(testLibs.testcontainers.elasticsearch)
    implementation(libs.elastic)
    implementation(libs.jackson.databind)
}
