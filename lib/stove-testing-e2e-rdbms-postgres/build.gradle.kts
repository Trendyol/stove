dependencies {
    api(projects.lib.stoveTestingE2eRdbms)
    implementation(libs.r2dbc.postgresql)
    implementation(libs.testcontainers.postgres)
}
