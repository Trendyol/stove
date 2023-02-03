plugins {}
dependencies {
    api(project(":lib:stove-testing-e2e"))
    implementation(testLibs.testcontainers.elasticsearch)
    implementation(libs.kotlinx.reactive)
    implementation(libs.elastic.highlevelclient)
    implementation(libs.elastic)
}
