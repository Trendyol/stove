dependencies {
    api(projects.lib.stoveTestingE2e)
    api(libs.couchbase.kotlin)
    api(libs.testcontainers.couchbase)
}

dependencies {
    testImplementation(libs.slf4j.simple)
}
