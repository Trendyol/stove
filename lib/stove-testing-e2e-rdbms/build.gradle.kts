dependencies {
    api(project(":lib:stove-testing-e2e"))
    api(libs.kotlinx.reactor)
    api(libs.r2dbc.spi)
    api(testLibs.testcontainers.jdbc)
    testImplementation(testLibs.mockito.kotlin)
}
