dependencies {
    api(project(":lib:stove-testing-e2e"))
    implementation(testLibs.testcontainers.couchbase)
    implementation(libs.couchbase.client)
    implementation(libs.kotlinx.reactive)
}

dependencies {
    testImplementation(libs.slf4j.simple)
}
