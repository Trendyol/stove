ext { set("publish", false) }

dependencies {
    api(project(":lib:stove-testing-e2e"))
    implementation(libs.kotlinx.reactor)
    implementation(libs.r2dbc.spi)
    implementation(testLibs.testcontainers.jdbc)
    testImplementation(testLibs.mockito.kotlin)
}
