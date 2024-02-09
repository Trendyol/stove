dependencies {
    api(projects.lib.stoveTestingE2e)
    api(libs.couchbase.kotlin)
    implementation(libs.testcontainers.couchbase)
}

dependencies {
    testImplementation(libs.slf4j.simple)
}
