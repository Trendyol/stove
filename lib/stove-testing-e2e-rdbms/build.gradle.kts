dependencies {
    api(projects.lib.stoveTestingE2e)
    api(libs.kotlinx.reactor)
    api(libs.r2dbc.spi)
    api(libs.testcontainers.jdbc)
    testImplementation(libs.mockito.kotlin)
}
