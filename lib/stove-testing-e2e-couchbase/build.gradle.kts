dependencies {
    api(projects.lib.stoveTestingE2e)
    implementation(libs.testcontainers.couchbase)
    implementation(libs.couchbase.client)
    implementation(libs.kotlinx.reactive)
}

dependencies {
    testImplementation(libs.slf4j.simple)
}
