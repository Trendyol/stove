dependencies {
    api(projects.lib.stoveTestingE2e)
    implementation(libs.couchbase.kotlin)
    implementation(libs.testcontainers.couchbase)
}

dependencies {
    testImplementation(libs.slf4j.simple)
}
