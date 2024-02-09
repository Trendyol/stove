dependencies {
    api(projects.lib.stoveTestingE2eRdbms)
    api(libs.r2dbc.postgresql)
    api(libs.testcontainers.postgres)
    testImplementation(libs.logback.classic)
}
