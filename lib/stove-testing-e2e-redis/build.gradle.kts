dependencies {
    api(projects.lib.stoveTestingE2e)
    implementation(libs.testcontainers.redis)
    implementation(libs.lettuce.core)
}
